package com.mindbridge.agent.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchSource;
import com.mindbridge.agent.domain.SourceStatus;
import com.mindbridge.agent.domain.SourceType;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.ResearchSourceRepository;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentParsingServiceTests {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 10L;
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    @Mock
    private ResearchProjectService projectService;

    @Mock
    private ResearchSourceRepository sourceRepository;

    private Path storageDir;
    private LocalResearchSourceStorage storage;
    private DocumentParsingService parsingService;
    private ResearchSourceService sourceService;
    private Map<Long, ResearchSource> sources;
    private AtomicLong ids;

    @BeforeEach
    void setUp() throws IOException {
        storageDir = Files.createTempDirectory("research-sources");
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getResearch().setSourceStorageDir(storageDir.toString());
        storage = new LocalResearchSourceStorage(properties);
        parsingService = new DocumentParsingService(List.of(
                new PdfDocumentParser(),
                new MarkdownDocumentParser(),
                new PlainTextDocumentParser()
        ));
        sources = new HashMap<>();
        ids = new AtomicLong(1);
        lenient().when(sourceRepository.save(any(ResearchSource.class))).thenAnswer(invocation -> {
            ResearchSource source = invocation.getArgument(0);
            if (source.getId() == null) {
                source.setId(ids.getAndIncrement());
            }
            sources.put(source.getId(), source);
            return source;
        });
        lenient().when(sourceRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(sources.get(invocation.getArgument(0))));
        lenient().when(sourceRepository.findByIdAndProject_IdAndOwner_Id(anyLong(), eq(PROJECT_ID), eq(USER_ID)))
                .thenAnswer(invocation -> Optional.ofNullable(sources.get(invocation.getArgument(0))));
        lenient().when(sourceRepository.findByFilenameAndProject_Id(any(), eq(PROJECT_ID)))
                .thenAnswer(invocation -> sources.values().stream()
                        .filter(source -> invocation.getArgument(0).equals(source.getFilename()))
                        .findFirst());
        sourceService = new ResearchSourceService(projectService, sourceRepository, storage, parsingService);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (storageDir == null || !Files.exists(storageDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(storageDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of temp storage
                }
            });
        }
    }

    @Test
    void parsesPdfPageByPage() throws IOException {
        ParsedDocument parsed = parsingService.parse("sample-paper.pdf", PDF_CONTENT_TYPE, readResource("sample-paper.pdf"));

        assertThat(parsed.sourceType()).isEqualTo(SourceType.PDF);
        assertValidSections(parsed);
        assertThat(parsed.sections()).filteredOn(section -> section.pageNumber() != null).isNotEmpty();
        assertThat(parsed.sections())
                .extracting(ParsedSection::pageNumber)
                .contains(1, 2);
        assertThat(parsed.sections().get(0).content()).contains("LoRA");
        assertThat(parsed.sections().get(1).content()).contains("QLoRA");
    }

    @Test
    void parsesMarkdownHeadings() throws IOException {
        ParsedDocument parsed = parsingService.parse("sample-note.md", "text/markdown", readResource("sample-note.md"));

        assertThat(parsed.sourceType()).isEqualTo(SourceType.MARKDOWN);
        assertThat(parsed.title()).isEqualTo("Adapter Choice");
        assertValidSections(parsed);
        assertThat(parsed.sections())
                .extracting(ParsedSection::heading)
                .contains("Adapter Choice", "Memory Constraint");
        assertThat(parsed.sections())
                .anySatisfy(section -> assertThat(section.content()).contains("trainable parameters"));
    }

    @Test
    void parsesTxtLineOffsets() throws IOException {
        byte[] bytes = readResource("sample-log.txt");
        String original = new String(bytes, StandardCharsets.UTF_8);
        ParsedDocument parsed = parsingService.parse("sample-log.txt", "text/plain", bytes);

        assertThat(parsed.sourceType()).isEqualTo(SourceType.TXT);
        assertValidSections(parsed);
        assertThat(parsed.sections()).isNotEmpty();
        assertThat(parsed.sections()).allSatisfy(section -> {
            assertThat(original.substring(section.startOffset(), section.endOffset())).isEqualTo(section.content());
            assertThat(section.pageNumber()).isNull();
        });
        assertThat(parsed.sections().get(0).content()).contains("epoch=1");
    }

    @Test
    void rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> parsingService.parse("notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(DocumentParsingService.UNSUPPORTED_FILE_MESSAGE);
    }

    @Test
    void corruptedPdfMarksSourceFailed() {
        ResearchProject project = project();
        when(projectService.requireOwnedProject(USER_ID, PROJECT_ID)).thenReturn(project);

        ResearchSource source = sourceService.createPending(
                USER_ID, PROJECT_ID, "broken.pdf", PDF_CONTENT_TYPE, "not-a-pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> sourceService.parse(USER_ID, PROJECT_ID, source.getId()))
                .isInstanceOf(DocumentParseException.class);
        assertThat(sourceRepository.findByFilenameAndProject_Id("broken.pdf", PROJECT_ID))
                .get()
                .extracting(ResearchSource::getStatus)
                .isEqualTo(SourceStatus.FAILED);
    }

    @Test
    void traversalFilenameDoesNotEscapeStorageDirectory() throws IOException {
        StoredResearchSource stored = storage.store(
                USER_ID, PROJECT_ID, "../../../etc/passwd.pdf", "pdf-bytes".getBytes(StandardCharsets.UTF_8));

        assertThat(stored.storageKey()).doesNotContain("..");
        Path storedFile = storageDir.resolve(stored.storageKey()).normalize();
        assertThat(storedFile.startsWith(storageDir.toAbsolutePath().normalize())).isTrue();
        assertThat(storedFile.getFileName().toString()).isNotEqualTo("passwd.pdf");
        assertThat(Files.readAllBytes(storedFile)).isEqualTo("pdf-bytes".getBytes(StandardCharsets.UTF_8));
        try (Stream<Path> paths = Files.walk(storageDir)) {
            assertThat(paths.map(Path::getFileName).map(Path::toString)).doesNotContain("passwd.pdf");
        }
    }

    @Test
    void missingStorageKeyDoesNotExposeHostPath() {
        assertThatThrownBy(() -> storage.load("missing-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ResearchSourceStorage.FILE_NOT_FOUND_MESSAGE)
                .hasMessageNotContaining(storageDir.toAbsolutePath().toString());
    }

    private void assertValidSections(ParsedDocument parsed) {
        assertThat(parsed.sections()).isNotEmpty();
        assertThat(parsed.sections()).allSatisfy(section -> {
            assertThat(section.order()).isNotNegative();
            assertThat(section.startOffset()).isNotNegative();
            assertThat(section.endOffset()).isGreaterThan(section.startOffset());
            assertThat(section.content()).isNotBlank();
        });
    }

    private byte[] readResource(String filename) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/documents/" + filename)) {
            assertThat(input).as("missing test resource %s", filename).isNotNull();
            return input.readAllBytes();
        }
    }

    private ResearchProject project() {
        UserAccount owner = new UserAccount();
        owner.setUsername("user-" + USER_ID);
        owner.setDisplayName("User " + USER_ID);
        owner.setPassword("secret");
        ResearchProject project = new ResearchProject();
        project.setId(PROJECT_ID);
        project.setOwner(owner);
        project.setName("LoRA vs QLoRA");
        project.setObjective("Choose an adapter method under 12GB VRAM.");
        return project;
    }
}
