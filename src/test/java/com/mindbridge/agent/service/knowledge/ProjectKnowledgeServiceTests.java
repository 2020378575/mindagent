package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchSource;
import com.mindbridge.agent.domain.SourceType;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import com.mindbridge.agent.repository.ResearchSourceRepository;
import com.mindbridge.agent.service.document.ParsedDocument;
import com.mindbridge.agent.service.document.ParsedSection;
import com.mindbridge.agent.service.document.ResearchSourceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectKnowledgeServiceTests {

    private static final Long PROJECT_A = 11L;
    private static final Long PROJECT_B = 22L;
    private static final Long SOURCE_A = 101L;
    private static final Long SOURCE_B = 202L;

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private ResearchSourceRepository sourceRepository;

    @Mock
    private ResearchSourceService sourceService;

    @Mock
    private ChromaGateway chromaGateway;

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private KnowledgeReranker reranker;

    private final List<KnowledgeChunk> store = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private ProjectKnowledgeService service;

    @BeforeEach
    void setUp() {
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getKnowledge().setUseChroma(false);
        properties.getKnowledge().setRerankerEnabled(false);
        properties.getKnowledge().setChunkSize(512);
        properties.getKnowledge().setChunkOverlap(32);

        lenient().when(embeddingClient.embed(anyString())).thenReturn(List.of());
        lenient().when(chromaGateway.query(any(), anyString(), anyInt())).thenReturn(List.of());
        lenient().when(reranker.rerank(anyString(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    List<SearchResult> candidates = invocation.getArgument(1);
                    int topK = invocation.getArgument(2);
                    return candidates.stream().limit(topK).toList();
                });
        lenient().when(chunkRepository.save(any(KnowledgeChunk.class))).thenAnswer(invocation -> {
            KnowledgeChunk chunk = invocation.getArgument(0);
            if (chunk.getId() == null) {
                chunk.setId(ids.getAndIncrement());
            }
            store.removeIf(existing -> existing.getId().equals(chunk.getId()));
            store.add(chunk);
            return chunk;
        });
        lenient().when(chunkRepository.findByProject_Id(anyLong())).thenAnswer(invocation -> {
            Long projectId = invocation.getArgument(0);
            return store.stream().filter(chunk -> projectId.equals(chunk.projectId())).toList();
        });
        lenient().when(chunkRepository.findById(anyLong())).thenAnswer(invocation -> store.stream()
                .filter(chunk -> invocation.getArgument(0).equals(chunk.getId()))
                .findFirst());
        lenient().when(chunkRepository.findByResearchSource_IdAndSourceIndexBetweenOrderBySourceIndexAsc(
                        anyLong(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    Long sourceId = invocation.getArgument(0);
                    int start = invocation.getArgument(1);
                    int end = invocation.getArgument(2);
                    return store.stream()
                            .filter(chunk -> sourceId.equals(chunk.sourceId()))
                            .filter(chunk -> chunk.getSourceIndex() >= start && chunk.getSourceIndex() <= end)
                            .toList();
                });
        lenient().when(sourceRepository.findByIdAndProject_Id(SOURCE_A, PROJECT_A))
                .thenReturn(Optional.of(source(SOURCE_A, PROJECT_A)));
        lenient().when(sourceRepository.findByIdAndProject_Id(SOURCE_B, PROJECT_B))
                .thenReturn(Optional.of(source(SOURCE_B, PROJECT_B)));

        service = new ProjectKnowledgeService(
                chunkRepository,
                sourceRepository,
                sourceService,
                properties,
                chromaGateway,
                embeddingClient,
                reranker,
                new ObjectMapper());
    }

    @Test
    void retrieveDoesNotLeakAcrossProjects() {
        service.ingest(PROJECT_A, SOURCE_A, document("unique-alpha evidence for LoRA adapters"));
        service.ingest(PROJECT_B, SOURCE_B, document("unique-beta evidence for QLoRA adapters"));

        List<SearchResult> leaked = service.retrieve(PROJECT_A, "unique-beta", 5);
        assertThat(leaked).allMatch(result -> PROJECT_A.equals(result.projectId()));
        assertThat(leaked)
                .extracting(SearchResult::content)
                .noneMatch(content -> content.contains("unique-beta"));

        List<SearchResult> owned = service.retrieve(PROJECT_A, "unique-alpha", 5);
        assertThat(owned).isNotEmpty();
        assertThat(owned).extracting(SearchResult::projectId).containsOnly(PROJECT_A);
        assertThat(owned).extracting(SearchResult::content).anyMatch(content -> content.contains("unique-alpha"));
        assertThat(owned).allSatisfy(result -> {
            assertThat(result.sourceId()).isEqualTo(SOURCE_A);
            assertThat(result.pageNumber()).isEqualTo(1);
            assertThat(result.endOffset()).isGreaterThan(result.startOffset());
        });
        verify(sourceService).markReady(SOURCE_A, 1, 1);
        verify(sourceService).markReady(SOURCE_B, 1, 1);
    }

    @Test
    void chunkingKeepsHeadingBoundaries() {
        ParsedDocument parsed = new ParsedDocument(
                "Adapter notes",
                SourceType.MARKDOWN,
                List.of(
                        new ParsedSection(0, null, "LoRA", 0, 24, "LoRA keeps adapters small."),
                        new ParsedSection(1, null, "QLoRA", 24, 55, "QLoRA uses 4-bit quantization.")
                ));

        service.ingest(PROJECT_A, SOURCE_A, parsed);
        List<SearchResult> results = service.retrieve(PROJECT_A, "QLoRA quantization", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).heading()).isEqualTo("QLoRA");
        assertThat(results.get(0).content()).contains("4-bit");
        assertThat(store)
                .extracting(KnowledgeChunk::getHeading)
                .containsExactly("LoRA", "QLoRA");
    }

    private ParsedDocument document(String content) {
        return new ParsedDocument(
                "sample-paper",
                SourceType.PDF,
                List.of(new ParsedSection(0, 1, "Introduction", 0, content.length(), content)));
    }

    private ResearchSource source(Long sourceId, Long projectId) {
        ResearchProject project = new ResearchProject();
        project.setId(projectId);
        project.setName("Project " + projectId);
        project.setObjective("Isolation test");
        ResearchSource source = new ResearchSource();
        source.setId(sourceId);
        source.setProject(project);
        source.setFilename("sample-paper.pdf");
        source.setSourceType(SourceType.PDF);
        return source;
    }
}
