package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import com.mindbridge.agent.service.knowledge.ChromaGateway;
import com.mindbridge.agent.service.knowledge.EmbeddingClient;
import com.mindbridge.agent.service.knowledge.KnowledgeReranker;
import com.mindbridge.agent.service.knowledge.ProjectKnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
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
class CrossProjectRetrievalHarnessTests {

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

        lenient().when(embeddingClient.embed(anyString())).thenReturn(List.of());
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
    void localFallbackDoesNotLeakWhenChromaIsDisabled() {
        when(chromaGateway.query(any(), anyString(), anyInt())).thenReturn(List.of());
        ingestBothProjects();

        assertNoLeakFromProjectA();
    }

    @Test
    void discardsForeignProjectCandidatesFromFaultyChroma() {
        ingestBothProjects();
        when(chromaGateway.query(eq(PROJECT_A), anyString(), anyInt())).thenReturn(List.of(
                new SearchResult(
                        99L,
                        PROJECT_B,
                        SOURCE_B,
                        "foreign",
                        SourceType.PDF,
                        2,
                        "Other project",
                        0,
                        18,
                        "unique-beta evidence",
                        0.99)
        ));

        assertNoLeakFromProjectA();
    }

    private void ingestBothProjects() {
        service.ingest(PROJECT_A, SOURCE_A, document("unique-alpha evidence"));
        service.ingest(PROJECT_B, SOURCE_B, document("unique-beta evidence"));
    }

    private void assertNoLeakFromProjectA() {
        List<SearchResult> results = service.retrieve(PROJECT_A, "unique-beta", 5);
        assertThat(results)
                .extracting(SearchResult::projectId)
                .containsOnly(PROJECT_A);
        assertThat(results)
                .extracting(SearchResult::content)
                .noneMatch(content -> content.contains("unique-beta"));
    }

    private ParsedDocument document(String content) {
        return new ParsedDocument(
                "note",
                SourceType.TXT,
                List.of(new ParsedSection(0, null, null, 0, content.length(), content)));
    }

    private ResearchSource source(Long sourceId, Long projectId) {
        ResearchProject project = new ResearchProject();
        project.setId(projectId);
        project.setName("Project " + projectId);
        project.setObjective("Cross-project harness");
        ResearchSource source = new ResearchSource();
        source.setId(sourceId);
        source.setProject(project);
        source.setFilename("note.txt");
        source.setSourceType(SourceType.TXT);
        return source;
    }
}
