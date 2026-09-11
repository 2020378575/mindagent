# EvidenceLab Four-Core Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 MindBridge 重构为 EvidenceLab 科研决策工作台，并以受控多 Agent、可恢复异步任务、三层研究记忆、位置感知的项目级 RAG 四项能力作为实现与简历验收主线。

**Architecture:** 保留 Spring Boot 3、Spring AI、MySQL/H2、Redis、Chroma 和固定顺序 Agent loop；以 `ResearchProject` 作为所有资料、任务、记忆、会话和决策的隔离边界。长耗时工作由持久化 `ResearchTask` 驱动，SSE 只订阅任务事件；模型输出先经过结构化解析和引用绑定校验，用户确认后才能成为正式决策。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Spring AI 1.0.0、Spring Data JPA、WebFlux/SSE、Redis、Chroma、PDFBox 3.0.3、JUnit 5、Mockito、AssertJ。

## Global Constraints

- 第一版只支持 PDF、Markdown 和 TXT；不接入联网论文搜索、DOCX、GraphRAG、自动训练和云执行沙箱。
- 包名暂时保留 `com.mindbridge.agent`，产品名、业务类型和用户文案改为 EvidenceLab；避免在同一阶段进行无业务收益的大规模包迁移。
- Agent 顺序和最大步数由代码控制；模型不得自由创建 Agent、自由选择任意工具或输出隐式思维链。
- 所有知识、任务、记忆、会话、决策和轨迹必须同时按所有者与 `projectId` 校验。
- 模型生成的决策只能保存为 `DRAFT`；只有用户确认后才能进入 `CONFIRMED`。
- 结构已知的模型 JSON 必须解析到字段明确的 Java record/class，不使用 `Map<String, Object>` 作为业务协议。
- Controller 只负责绑定、入口权限和响应转换；编排放 Service，数据访问放 Repository，Chroma/Redis/模型调用放 Gateway 或 Client。
- 同一字段键、路由片段、状态键、错误文本或展示文案出现两次及以上时，提取到最小合理作用域的常量。
- 所有异步步骤必须明确超时、重试次数、幂等键、状态变化和失败原因；失败不能伪装成成功或空结果。
- 已确认决策不得静默覆盖；重新分析必须创建新版本。
- 第一版评测目标沿用设计规格：路由准确率 ≥90%、Recall@5 ≥85%、引用支持率 ≥95%、结构化输出成功率 ≥98%、跨项目泄漏为 0。
- 不主动提交或覆盖用户当前未跟踪的源码；每个任务只暂存该任务明确列出的文件。

---

## Execution Prerequisite: Preserve the Current Baseline

截至 2026-09-11，以下目录仍显示为未跟踪：

```text
src/main/java/com/mindbridge/agent/service/knowledge/
src/main/java/com/mindbridge/agent/service/mcp/
src/main/java/com/mindbridge/agent/service/memory/
src/main/resources/
src/test/
```

开始 Task 1 前先运行：

```bash
git status --short
git ls-files src/main/java/com/mindbridge/agent/service/knowledge
```

预期：明确区分已提交基线与用户未提交成果。若上述目录仍未跟踪，停止实施并请用户决定是先提交、忽略还是删除；执行 Agent 不得自行处置。

建议在基线安全后，从新工作树或分支 `codex/evidencelab-refactor` 执行。本计划中的提交命令不会执行 `git push`。

## Target File Structure

```text
src/main/java/com/mindbridge/agent/
├── controller/
│   ├── ProjectController.java
│   ├── ResearchSourceController.java
│   ├── ResearchTaskController.java
│   ├── ResearchAssistantController.java
│   ├── DecisionController.java
│   └── ExperimentController.java
├── domain/
│   ├── ResearchProject.java
│   ├── ResearchSource.java
│   ├── ResearchTask.java
│   ├── ResearchTaskCheckpoint.java
│   ├── ResearchMemoryItem.java
│   ├── ExperimentRun.java
│   ├── DecisionRecord.java
│   ├── DecisionEvidence.java
│   ├── ProjectStatus.java
│   ├── SourceType.java
│   ├── SourceStatus.java
│   ├── ResearchTaskStatus.java
│   ├── ResearchTaskType.java
│   ├── ResearchTaskStage.java
│   ├── ResearchMemoryType.java
│   ├── MemoryValidationStatus.java
│   ├── ResearchMemorySourceType.java
│   ├── IntentType.java
│   ├── DecisionStatus.java
│   ├── EvidenceStance.java
│   ├── ExperimentStatus.java
│   └── ReviewVerdict.java
├── repository/
│   └── repositories matching the new domain entities
├── service/
│   ├── project/ResearchProjectService.java
│   ├── task/ResearchTaskService.java
│   ├── task/ResearchTaskExecutor.java
│   ├── task/ResearchTaskEventService.java
│   ├── document/DocumentParser.java
│   ├── document/PdfDocumentParser.java
│   ├── document/MarkdownDocumentParser.java
│   ├── document/PlainTextDocumentParser.java
│   ├── document/DocumentParsingService.java
│   ├── knowledge/ProjectKnowledgeService.java
│   ├── memory/ResearchWorkingMemoryService.java
│   ├── memory/ProjectRecentMemoryService.java
│   ├── memory/ResearchLongTermMemoryService.java
│   ├── decision/DecisionService.java
│   ├── decision/DecisionValidationService.java
│   └── agent/
│       ├── ResearchContextAgent.java
│       ├── SupervisorAgent.java
│       ├── EvidenceAgent.java
│       ├── EvidenceCriticAgent.java
│       ├── ResearchAssistantAgent.java
│       └── DecisionAgent.java
└── dto/
    └── project, task, source, decision and experiment request/response records
```

职责原则：

- 不创建万能 `utils` 或大而全的 `EvidenceLabService`。
- `ResearchTaskService` 管状态，不直接实现每个 Agent 的业务。
- `DocumentParsingService` 只选择解析策略，不执行索引。
- `ProjectKnowledgeService` 只处理入库、检索、重排和相邻片段扩展。
- `DecisionValidationService` 只做可确定校验，不替代 EvidenceCriticAgent 的语义审查。

---

### Task 1: Establish the Research Project Boundary

**Files:**
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchProject.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ProjectStatus.java`
- Create: `src/main/java/com/mindbridge/agent/repository/ResearchProjectRepository.java`
- Create: `src/main/java/com/mindbridge/agent/service/project/ResearchProjectService.java`
- Create: `src/main/java/com/mindbridge/agent/dto/CreateResearchProjectRequest.java`
- Create: `src/main/java/com/mindbridge/agent/dto/ResearchProjectResponse.java`
- Create: `src/main/java/com/mindbridge/agent/controller/ProjectController.java`
- Modify: `src/main/java/com/mindbridge/agent/domain/ChatSession.java`
- Test: `src/test/java/com/mindbridge/agent/service/project/ResearchProjectServiceTests.java`
- Test: `src/test/java/com/mindbridge/agent/harness/ProjectIsolationApiTests.java`

**Interfaces:**
- Consumes: `UserAccountRepository`, `CurrentUser`, Spring Data JPA.
- Produces: `ResearchProjectService.requireOwnedProject(Long userId, Long projectId)`, which every later task must use before accessing project data.

- [ ] **Step 1: Write the project ownership tests**

```java
@Test
void returnsProjectOwnedByCurrentUser() {
    ResearchProject project = project(10L, user(7L));
    when(repository.findByIdAndOwner_Id(10L, 7L)).thenReturn(Optional.of(project));

    assertThat(service.requireOwnedProject(7L, 10L)).isSameAs(project);
}

@Test
void hidesProjectOwnedByAnotherUser() {
    when(repository.findByIdAndOwner_Id(10L, 7L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.requireOwnedProject(7L, 10L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Research project not found");
}
```

- [ ] **Step 2: Run the focused tests and verify failure**

Run:

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchProjectServiceTests test
```

Expected: FAIL because `ResearchProjectService` and its repository do not exist.

- [ ] **Step 3: Add the project contract**

```java
public enum ProjectStatus {
    ACTIVE,
    ARCHIVED
}

public interface ResearchProjectRepository extends JpaRepository<ResearchProject, Long> {
    Optional<ResearchProject> findByIdAndOwner_Id(Long projectId, Long ownerId);
    List<ResearchProject> findByOwner_IdOrderByUpdatedAtDesc(Long ownerId);
}

public record CreateResearchProjectRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 4000) String objective,
        @Size(max = 4000) String constraints
) {
}
```

`ResearchProject` 必须包含 `id`、`owner`、`name`、`objective`、`constraints`、`status`、`createdAt`、`updatedAt`，并在表上为 `owner_id, updated_at` 建索引。为 `ChatSession` 新增非空 `project` 关联；开发环境旧演示数据不迁移。

`ResearchProjectService` 暴露：

```java
public ResearchProject create(Long userId, CreateResearchProjectRequest request);
public List<ResearchProject> list(Long userId);
public ResearchProject requireOwnedProject(Long userId, Long projectId);
public ResearchProject archive(Long userId, Long projectId);
```

`ProjectController` 使用常量 `PROJECTS_PATH = "/api/projects"`，只调用 Service，不直接访问 Repository。

- [ ] **Step 4: Verify ownership at API level**

```java
webTestClient.get()
        .uri("/api/projects/{projectId}", otherUsersProjectId)
        .headers(headers -> headers.setBasicAuth("student", "student123"))
        .exchange()
        .expectStatus().isNotFound();
```

Run:

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchProjectServiceTests,ProjectIsolationApiTests test
```

Expected: PASS; another user's project is not distinguishable from a missing project.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mindbridge/agent/domain/ResearchProject.java src/main/java/com/mindbridge/agent/domain/ProjectStatus.java src/main/java/com/mindbridge/agent/domain/ChatSession.java src/main/java/com/mindbridge/agent/repository/ResearchProjectRepository.java src/main/java/com/mindbridge/agent/service/project/ResearchProjectService.java src/main/java/com/mindbridge/agent/dto/CreateResearchProjectRequest.java src/main/java/com/mindbridge/agent/dto/ResearchProjectResponse.java src/main/java/com/mindbridge/agent/controller/ProjectController.java src/test/java/com/mindbridge/agent/service/project/ResearchProjectServiceTests.java src/test/java/com/mindbridge/agent/harness/ProjectIsolationApiTests.java
git commit -m "feat: add research project ownership boundary

AI-Co-Authored-By: Codex"
```

---

### Task 2: Build Position-Aware Document Parsing

**Files:**
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchSource.java`
- Create: `src/main/java/com/mindbridge/agent/domain/SourceType.java`
- Create: `src/main/java/com/mindbridge/agent/domain/SourceStatus.java`
- Create: `src/main/java/com/mindbridge/agent/repository/ResearchSourceRepository.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/ResearchSourceService.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/DocumentParser.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/ParsedDocument.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/ParsedSection.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/PdfDocumentParser.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/MarkdownDocumentParser.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/PlainTextDocumentParser.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/DocumentParsingService.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/DocumentParseException.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/ResearchSourceStorage.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/LocalResearchSourceStorage.java`
- Create: `src/main/java/com/mindbridge/agent/service/document/StoredResearchSource.java`
- Replace: `src/main/java/com/mindbridge/agent/service/knowledge/KnowledgeFileService.java`
- Test: `src/test/java/com/mindbridge/agent/service/document/DocumentParsingServiceTests.java`
- Test resources: `src/test/resources/documents/sample-paper.pdf`, `sample-note.md`, `sample-log.txt`

**Interfaces:**
- Consumes: `ResearchProjectService.requireOwnedProject`.
- Produces: `ParsedDocument` with stable source positions, consumed by Task 3.

- [ ] **Step 1: Define and test the parser contract**

```java
public interface DocumentParser {
    boolean supports(String filename, String contentType);
    ParsedDocument parse(String filename, byte[] content);
}

public record ParsedDocument(
        String title,
        SourceType sourceType,
        List<ParsedSection> sections
) {
}

public record ParsedSection(
        int order,
        Integer pageNumber,
        String heading,
        int startOffset,
        int endOffset,
        String content
) {
}
```

Required assertions:

```java
assertThat(parsed.sections()).allSatisfy(section -> {
    assertThat(section.order()).isNotNegative();
    assertThat(section.startOffset()).isNotNegative();
    assertThat(section.endOffset()).isGreaterThan(section.startOffset());
    assertThat(section.content()).isNotBlank();
});
assertThat(parsed.sections()).filteredOn(section -> section.pageNumber() != null).isNotEmpty();
```

- [ ] **Step 2: Verify the test fails**

Run:

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=DocumentParsingServiceTests test
```

Expected: FAIL because parser strategies and `ParsedDocument` do not exist.

- [ ] **Step 3: Implement unified parsing**

`DocumentParsingService` must use the injected strategy list:

```java
@Service
public class DocumentParsingService {
    private static final String UNSUPPORTED_FILE_MESSAGE = "Unsupported research source format";

    private final List<DocumentParser> parsers;

    public DocumentParsingService(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public ParsedDocument parse(String filename, String contentType, byte[] content) {
        return parsers.stream()
                .filter(parser -> parser.supports(filename, contentType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(UNSUPPORTED_FILE_MESSAGE))
                .parse(filename, content);
    }
}
```

`PdfDocumentParser` must iterate page-by-page with `PDFTextStripper#setStartPage` and `setEndPage`; it must not call `getText(document)` once for the whole file. Markdown parsing preserves heading hierarchy. TXT parsing records line-based offsets in `startOffset/endOffset`.

`ResearchSource` stores project, owner, filename, content type, source type, status, failure message, section count, chunk count and timestamps. Status values are `PENDING`, `PARSING`, `INDEXING`, `READY`, `FAILED`.

`ResearchSourceService` exposes:

```java
public ResearchSource createPending(
        Long userId,
        Long projectId,
        String filename,
        String contentType,
        byte[] content
);
public ResearchSource markParsing(Long sourceId);
public ResearchSource markReady(Long sourceId, int sectionCount, int chunkCount);
public ResearchSource markFailed(Long sourceId, String safeFailureMessage);
public ResearchSource requireOwnedSource(Long userId, Long projectId, Long sourceId);
public ParsedDocument parse(
        Long userId,
        Long projectId,
        Long sourceId
);
```

`ResearchSource` also stores an opaque `storageKey`, SHA-256 digest and byte size. `createPending` writes the raw file through:

```java
public interface ResearchSourceStorage {
    StoredResearchSource store(Long ownerId, Long projectId, String filename, byte[] content);
    byte[] load(String storageKey);
    void delete(String storageKey);
}

public record StoredResearchSource(
        String storageKey,
        String sha256,
        long sizeBytes
) {
}
```

`LocalResearchSourceStorage` stores files under the configured EvidenceLab data directory using generated opaque names; it must never concatenate the submitted filename into a filesystem path. The raw file must survive application restart so an ingestion task can resume.

`parse` loads bytes by `storageKey`, changes `PENDING -> PARSING -> INDEXING` and returns the parsed document. Task 3 marks the source `READY` only after all chunks and Chroma metadata are written. If parsing fails, it calls `markFailed` before rethrowing `DocumentParseException`. Task 4 will place this operation behind a persistent `SOURCE_INGESTION` task.

- [ ] **Step 4: Test failure state preservation**

```java
ResearchSource source = sourceService.createPending(
        userId, projectId, "broken.pdf", PDF, invalidBytes);
assertThatThrownBy(() -> sourceService.parse(
        userId, projectId, source.getId()))
        .isInstanceOf(DocumentParseException.class);
assertThat(sourceRepository.findByFilenameAndProject_Id("broken.pdf", projectId))
        .get()
        .extracting(ResearchSource::getStatus)
        .isEqualTo(SourceStatus.FAILED);
```

Do not annotate the whole parse-and-store workflow with one database transaction. Persist source status before parsing and update it after each step so failure state remains observable.

- [ ] **Step 5: Run tests**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=DocumentParsingServiceTests test
```

Expected: PASS for page-aware PDF, heading-aware Markdown, line-aware TXT and corrupted PDF failure.

Also assert that a filename containing `../` cannot change the generated storage directory and that loading by a nonexistent storage key fails without exposing an absolute host path.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mindbridge/agent/domain/ResearchSource.java src/main/java/com/mindbridge/agent/domain/SourceType.java src/main/java/com/mindbridge/agent/domain/SourceStatus.java src/main/java/com/mindbridge/agent/repository/ResearchSourceRepository.java src/main/java/com/mindbridge/agent/service/document src/main/java/com/mindbridge/agent/service/knowledge/KnowledgeFileService.java src/test/java/com/mindbridge/agent/service/document src/test/resources/documents
git commit -m "feat: add position-aware research document parsing

AI-Co-Authored-By: Codex"
```

---

### Task 3: Isolate and Upgrade Hybrid RAG by Project

**Files:**
- Modify: `src/main/java/com/mindbridge/agent/domain/KnowledgeChunk.java`
- Modify: `src/main/java/com/mindbridge/agent/repository/KnowledgeChunkRepository.java`
- Create: `src/main/java/com/mindbridge/agent/service/knowledge/ProjectKnowledgeService.java`
- Modify: `src/main/java/com/mindbridge/agent/service/knowledge/KnowledgeChunker.java`
- Modify: `src/main/java/com/mindbridge/agent/service/knowledge/SearchResult.java`
- Modify: `src/main/java/com/mindbridge/agent/service/knowledge/ChromaGateway.java`
- Modify: `src/main/java/com/mindbridge/agent/service/knowledge/Bm25Scorer.java`
- Modify: `src/main/java/com/mindbridge/agent/service/knowledge/KnowledgeReranker.java`
- Test: `src/test/java/com/mindbridge/agent/service/knowledge/ProjectKnowledgeServiceTests.java`
- Test: `src/test/java/com/mindbridge/agent/harness/CrossProjectRetrievalHarnessTests.java`

**Interfaces:**
- Consumes: `ResearchSource`, `ParsedDocument`, `ParsedSection`.
- Produces: `ProjectKnowledgeService.ingest(Long projectId, Long sourceId, ParsedDocument document)` and `retrieve(Long projectId, String query, int topK)`.

- [ ] **Step 1: Write cross-project leakage tests**

```java
service.ingest(projectAId, sourceAId, document("unique-alpha evidence"));
service.ingest(projectBId, sourceBId, document("unique-beta evidence"));

assertThat(service.retrieve(projectAId, "unique-beta", 5))
        .extracting(SearchResult::projectId)
        .containsOnly(projectAId);
assertThat(service.retrieve(projectAId, "unique-beta", 5))
        .extracting(SearchResult::content)
        .noneMatch(content -> content.contains("unique-beta"));
```

The harness must run once with Chroma disabled and once with a stub Chroma gateway returning a foreign-project candidate.

- [ ] **Step 2: Verify the leakage test fails**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ProjectKnowledgeServiceTests,CrossProjectRetrievalHarnessTests test
```

Expected: FAIL because current retrieval calls `findAll()` and Chroma queries do not carry `projectId`.

- [ ] **Step 3: Extend chunk and search contracts**

`KnowledgeChunk` gains `project`, `researchSource`, `pageNumber`, `heading`, `startOffset`, `endOffset` and `sourceType`.

```java
public record SearchResult(
        Long chunkId,
        Long projectId,
        Long sourceId,
        String sourceTitle,
        SourceType sourceType,
        Integer pageNumber,
        String heading,
        int startOffset,
        int endOffset,
        String content,
        double score
) {
}

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    List<KnowledgeChunk> findByProject_Id(Long projectId);
    List<KnowledgeChunk> findByResearchSource_IdOrderBySourceIndexAsc(Long sourceId);
    List<KnowledgeChunk> findByResearchSource_IdAndSourceIndexBetweenOrderBySourceIndexAsc(
            Long sourceId, int startIndex, int endIndex);
    void deleteByResearchSource_Id(Long sourceId);
}
```

- [ ] **Step 4: Implement project-first retrieval**

```java
public List<SearchResult> retrieve(Long projectId, String query, int topK) {
    if (projectId == null || topK <= 0 || query == null || query.isBlank()) {
        return List.of();
    }
    List<KnowledgeChunk> projectChunks = repository.findByProject_Id(projectId);
    List<SearchResult> vector = vectorCandidates(projectId, query, candidateLimit(topK), projectChunks);
    List<SearchResult> keyword = bm25Scorer.rank(query, projectChunks, candidateLimit(topK));
    return expandBestContext(projectId, reranker.rerank(query, merge(vector, keyword), topK), topK);
}
```

`ChromaGateway.query` becomes:

```java
List<SearchResult> query(Long projectId, String query, int limit);
void mirror(KnowledgeChunk chunk);
void deleteSource(Long projectId, Long sourceId);
```

The gateway must store `projectId` and `sourceId` as metadata and include a Chroma `where` filter. `ProjectKnowledgeService` must defensively discard any returned item whose `projectId` differs even when the gateway is faulty.

After relational chunks and vector metadata are written successfully, call `ResearchSourceService.markReady(sourceId, sectionCount, chunkCount)`. If both vector and local index creation fail, call `markFailed`; if embedding or Chroma fails but BM25 chunks are available, mark the source `READY` and record vector degradation on the ingestion task.

- [ ] **Step 5: Preserve source location during chunking**

`KnowledgeChunker` changes from `List<String>` to:

```java
public record PositionedChunk(
        int sourceIndex,
        Integer pageNumber,
        String heading,
        int startOffset,
        int endOffset,
        String content
) {
}

public List<PositionedChunk> chunk(ParsedDocument document, int chunkSize, int overlap);
```

Chunks may overlap within the same `ParsedSection`, but must not merge unrelated pages or headings merely to reach the target size.

- [ ] **Step 6: Verify hybrid retrieval and isolation**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=KnowledgeRerankerTests,ProjectKnowledgeServiceTests,CrossProjectRetrievalHarnessTests test
```

Expected: PASS; vector and BM25 routes only rank current-project chunks, and every result contains a resolvable source location.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mindbridge/agent/domain/KnowledgeChunk.java src/main/java/com/mindbridge/agent/repository/KnowledgeChunkRepository.java src/main/java/com/mindbridge/agent/service/knowledge src/test/java/com/mindbridge/agent/service/knowledge src/test/java/com/mindbridge/agent/harness/CrossProjectRetrievalHarnessTests.java
git commit -m "feat: add project-isolated position-aware rag

AI-Co-Authored-By: Codex"
```

---

### Task 4: Add Recoverable Asynchronous Research Tasks

**Files:**
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchTask.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchTaskCheckpoint.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchTaskStatus.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchTaskType.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchTaskStage.java`
- Create: `src/main/java/com/mindbridge/agent/repository/ResearchTaskRepository.java`
- Create: `src/main/java/com/mindbridge/agent/repository/ResearchTaskCheckpointRepository.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/ResearchTaskService.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/ResearchTaskExecutor.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/ResearchTaskEventService.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/ResearchTaskHandler.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/ResearchTaskHandlerRegistry.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/TaskExecutionResult.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/TransientTaskException.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/TaskCheckpointPayload.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/SourceIngestionCheckpoint.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/SourceIngestionTaskHandler.java`
- Create: `src/main/java/com/mindbridge/agent/config/ResearchTaskExecutorConfig.java`
- Create: `src/main/java/com/mindbridge/agent/dto/CreateResearchTaskRequest.java`
- Create: `src/main/java/com/mindbridge/agent/dto/ResearchTaskResponse.java`
- Create: `src/main/java/com/mindbridge/agent/dto/ResearchTaskEvent.java`
- Create: `src/main/java/com/mindbridge/agent/dto/ResearchSourceResponse.java`
- Create: `src/main/java/com/mindbridge/agent/controller/ResearchTaskController.java`
- Create: `src/main/java/com/mindbridge/agent/controller/ResearchSourceController.java`
- Modify: `src/main/java/com/mindbridge/agent/config/MindBridgeProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/mindbridge/agent/service/task/ResearchTaskServiceTests.java`
- Test: `src/test/java/com/mindbridge/agent/harness/ResearchTaskRecoveryHarnessTests.java`

**Interfaces:**
- Consumes: project ownership from Task 1; later receives checkpoint callbacks from the Agent runtime.
- Produces: durable task lifecycle and SSE event replay.

- [ ] **Step 1: Write lifecycle and idempotency tests**

```java
ResearchTask first = service.create(userId, projectId,
        new CreateResearchTaskRequest(
                "request-001", ResearchTaskType.DECISION, "LoRA or QLoRA?",
                null, null, null));
ResearchTask duplicate = service.create(userId, projectId,
        new CreateResearchTaskRequest(
                "request-001", ResearchTaskType.DECISION, "LoRA or QLoRA?",
                null, null, null));

assertThat(duplicate.getId()).isEqualTo(first.getId());
assertThat(first.getStatus()).isEqualTo(ResearchTaskStatus.PENDING);
```

Recovery test uses two executor instances over the same H2 database to model a process restart:

```java
Long decisionId = 99L;
when(decisionHandler.execute(any(), any())).thenAnswer(invocation -> {
    taskService.saveCheckpoint(
            taskId,
            1,
            "TEST_DECISION_HANDLER",
            ResearchTaskStage.CRITIC,
            criticCheckpoint());
    throw new TransientTaskException("simulated model disconnect");
});
firstExecutor.execute(taskId);

reset(decisionHandler);
when(decisionHandler.type()).thenReturn(ResearchTaskType.DECISION);
when(decisionHandler.execute(any(), any()))
        .thenReturn(new TaskExecutionResult(ResearchTaskStatus.SUCCEEDED, decisionId));
ResearchTaskExecutor restartedExecutor = newExecutorUsingSameRepositories(decisionHandler);
restartedExecutor.resumeIncompleteTasks();

assertThat(taskRepository.findById(taskId)).get()
        .extracting(ResearchTask::getStatus)
        .isEqualTo(ResearchTaskStatus.SUCCEEDED);
assertThat(checkpointRepository.findByTask_IdOrderByStepNumber(taskId))
        .extracting(ResearchTaskCheckpoint::getStage)
        .contains(CRITIC);
```

Define the test payload and helper explicitly:

```java
private record TestCheckpointPayload(
        ResearchTaskStage stage
) implements TaskCheckpointPayload {
}

private TaskCheckpointPayload criticCheckpoint() {
    return new TestCheckpointPayload(ResearchTaskStage.CRITIC);
}

private ResearchTaskExecutor newExecutorUsingSameRepositories(ResearchTaskHandler handler) {
    return new ResearchTaskExecutor(
            taskService,
            new ResearchTaskHandlerRegistry(List.of(handler)),
            taskEventService,
            taskExecutor);
}
```

The second executor must read the persisted checkpoint rather than sharing in-memory state with the first.

- [ ] **Step 2: Verify tests fail**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchTaskServiceTests,ResearchTaskRecoveryHarnessTests test
```

Expected: FAIL because no persistent task model exists.

- [ ] **Step 3: Implement task state**

```java
public enum ResearchTaskStatus {
    PENDING,
    RUNNING,
    WAITING_FOR_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

public enum ResearchTaskType {
    SOURCE_INGESTION,
    EVIDENCE_QUERY,
    DECISION,
    RESULT_REVIEW
}

public enum ResearchTaskStage {
    SOURCE_STORAGE,
    PARSING,
    INDEXING,
    CONTEXT,
    EVIDENCE,
    CRITIC,
    DECISION,
    REVIEW
}

public record CreateResearchTaskRequest(
        @NotBlank String idempotencyKey,
        @NotNull ResearchTaskType type,
        @Size(max = 4000) String question,
        Long sourceId,
        Long decisionId,
        Long experimentId
) {
}
```

`ResearchTask` stores `publicId`, `idempotencyKey`, project, owner, type, status, current stage, progress percentage, request text, result reference, error code, safe error message, attempt count and timestamps. Add a unique constraint on `owner_id, project_id, idempotency_key`.

`ResearchTaskCheckpoint` stores task, step number, stage, status, structured result JSON, safe observation, startedAt and completedAt. Checkpoint JSON has one strong Java record per stage; do not deserialize known structures into maps.

```java
public interface TaskCheckpointPayload {
    ResearchTaskStage stage();
}

public record SourceIngestionCheckpoint(
        ResearchTaskStage stage,
        Long sourceId,
        int sectionCount,
        int chunkCount,
        boolean vectorIndexAvailable
) implements TaskCheckpointPayload {
}
```

`ResearchTaskService` exposes:

```java
public ResearchTask create(Long userId, Long projectId, CreateResearchTaskRequest request);
public ResearchTask requireOwnedTask(Long userId, Long projectId, String taskPublicId);
public boolean claimPendingTask(Long taskId);
public void saveCheckpoint(
        Long taskId,
        int stepNumber,
        String actor,
        ResearchTaskStage stage,
        TaskCheckpointPayload payload
);
public void markWaitingForConfirmation(Long taskId, Long resultReferenceId);
public void markSucceeded(Long taskId, Long resultReferenceId);
public void markFailed(Long taskId, String errorCode, String safeMessage);
public ResearchTask retry(Long userId, Long projectId, String taskPublicId);
public ResearchTask cancel(Long userId, Long projectId, String taskPublicId);
public void ensureActive(Long taskId);
```

- [ ] **Step 4: Separate task execution from SSE**

```java
@Service
public class ResearchTaskExecutor {
    public void submit(Long taskId);
    public void execute(Long taskId);

    @EventListener(ApplicationReadyEvent.class)
    public void resumeIncompleteTasks();
}

@Service
public class ResearchTaskEventService {
    public void publish(ResearchTaskEvent event);
    public Flux<ServerSentEvent<ResearchTaskEvent>> stream(
            Long userId,
            Long projectId,
            String taskPublicId
    );
}

public interface ResearchTaskHandler {
    ResearchTaskType type();
    TaskExecutionResult execute(
            ResearchTask task,
            Optional<ResearchTaskCheckpoint> latestCheckpoint
    );
}

public record TaskExecutionResult(
        ResearchTaskStatus status,
        Long resultReferenceId
) {
}
```

`ResearchTaskHandlerRegistry` builds an immutable `EnumMap<ResearchTaskType, ResearchTaskHandler>` at startup and fails startup if two handlers claim the same type. `ResearchTaskExecutor` claims the task atomically, resolves one handler from the registry and applies its `TaskExecutionResult`. `SourceIngestionTaskHandler` calls `ResearchSourceService.parse` and `ProjectKnowledgeService.ingest`; Task 6 adds handlers for query, decision and review.

REST contract:

```text
POST /api/projects/{projectId}/tasks
GET  /api/projects/{projectId}/tasks/{taskPublicId}
GET  /api/projects/{projectId}/tasks/{taskPublicId}/events
POST /api/projects/{projectId}/tasks/{taskPublicId}/retry
POST /api/projects/{projectId}/tasks/{taskPublicId}/cancel
POST /api/projects/{projectId}/sources
GET  /api/projects/{projectId}/sources
```

The multipart source upload endpoint creates `ResearchSource` and `SOURCE_INGESTION` task records, then returns HTTP 202 with both public IDs. Repeated route fragments belong in controller constants. SSE events are projections of persisted state; losing the SSE connection must not cancel the task.

`ResearchTaskExecutorConfig` provides a named `ThreadPoolTaskExecutor` using `mindbridge.task.worker-count`. `ResearchTaskService.claimPendingTask(Long taskId)` must perform an atomic `PENDING -> RUNNING` update so startup recovery and a live submit cannot execute the same task concurrently.

- [ ] **Step 5: Define retry and recovery rules**

- `PENDING` and stale `RUNNING` tasks resume on startup.
- Automatic retry count is one for transient model, Chroma or parser dependency failures.
- Validation, authorization and unsupported-format failures are not retried.
- A retry resumes from the last successful checkpoint.
- `WAITING_FOR_CONFIRMATION`, `SUCCEEDED`, `FAILED` and `CANCELLED` are terminal for automatic execution.
- Cancellation is cooperative: check task status between Agent stages and before external calls.

Add configuration:

```yaml
mindbridge:
  task:
    worker-count: 2
    stage-timeout: 60s
    stale-running-after: 5m
    max-attempts: 2
```

- [ ] **Step 6: Run recovery tests**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchTaskServiceTests,ResearchTaskRecoveryHarnessTests test
```

Expected: PASS; duplicate requests return one task, and a restarted task continues after the last completed checkpoint.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mindbridge/agent/domain/ResearchTask.java src/main/java/com/mindbridge/agent/domain/ResearchTaskCheckpoint.java src/main/java/com/mindbridge/agent/domain/ResearchTaskStatus.java src/main/java/com/mindbridge/agent/domain/ResearchTaskType.java src/main/java/com/mindbridge/agent/domain/ResearchTaskStage.java src/main/java/com/mindbridge/agent/repository/ResearchTaskRepository.java src/main/java/com/mindbridge/agent/repository/ResearchTaskCheckpointRepository.java src/main/java/com/mindbridge/agent/service/task src/main/java/com/mindbridge/agent/dto/CreateResearchTaskRequest.java src/main/java/com/mindbridge/agent/dto/ResearchTaskResponse.java src/main/java/com/mindbridge/agent/dto/ResearchTaskEvent.java src/main/java/com/mindbridge/agent/dto/ResearchSourceResponse.java src/main/java/com/mindbridge/agent/controller/ResearchTaskController.java src/main/java/com/mindbridge/agent/controller/ResearchSourceController.java src/main/java/com/mindbridge/agent/config/MindBridgeProperties.java src/main/java/com/mindbridge/agent/config/ResearchTaskExecutorConfig.java src/main/resources/application.yml src/test/java/com/mindbridge/agent/service/task src/test/java/com/mindbridge/agent/harness/ResearchTaskRecoveryHarnessTests.java
git commit -m "feat: add recoverable research task execution

AI-Co-Authored-By: Codex"
```

---

### Task 5: Implement Three-Layer Research Memory

**Files:**
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchMemoryItem.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchMemoryType.java`
- Create: `src/main/java/com/mindbridge/agent/domain/MemoryValidationStatus.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ResearchMemorySourceType.java`
- Create: `src/main/java/com/mindbridge/agent/repository/ResearchMemoryItemRepository.java`
- Create: `src/main/java/com/mindbridge/agent/service/memory/ResearchWorkingMemoryService.java`
- Create: `src/main/java/com/mindbridge/agent/service/memory/ProjectRecentMemoryService.java`
- Create: `src/main/java/com/mindbridge/agent/service/memory/ResearchLongTermMemoryService.java`
- Create: `src/main/java/com/mindbridge/agent/service/memory/ResearchWorkingMemory.java`
- Create: `src/main/java/com/mindbridge/agent/service/memory/ResearchMemoryBundle.java`
- Create: `src/main/java/com/mindbridge/agent/service/memory/ResearchProjectEvent.java`
- Create: `src/main/java/com/mindbridge/agent/service/memory/ResearchMemoryMatch.java`
- Create: `src/main/java/com/mindbridge/agent/service/memory/ValidatedResearchMemory.java`
- Modify: `src/main/java/com/mindbridge/agent/service/memory/UserMemoryChromaGateway.java`
- Delete after migration: `src/main/java/com/mindbridge/agent/domain/UserMemoryItem.java`
- Delete after migration: `src/main/java/com/mindbridge/agent/domain/UserMemoryType.java`
- Delete after migration: `src/main/java/com/mindbridge/agent/service/memory/UserProfileMemoryService.java`
- Test: `src/test/java/com/mindbridge/agent/service/memory/ResearchMemoryServiceTests.java`
- Test: `src/test/java/com/mindbridge/agent/harness/ResearchMemoryPromotionHarnessTests.java`

**Interfaces:**
- Consumes: task checkpoints and projects; Task 7 will call its promotion API after confirming decisions or reviews.
- Produces: `ResearchMemoryBundle` for ResearchContextAgent.

- [ ] **Step 1: Write promotion-gate tests**

```java
ResearchMemoryItem promoted = longTermMemory.rememberValidated(
        new ValidatedResearchMemory(
                projectId,
                ResearchMemorySourceType.DECISION,
                confirmedDecisionId,
                MemoryValidationStatus.CONFIRMED,
                "QLoRA reduced peak memory under the project constraints",
                List.of(sourceChunkId)));
assertThat(promoted.getValidationStatus()).isEqualTo(MemoryValidationStatus.CONFIRMED);
```

Invalidation test:

```java
longTermMemory.markRefuted(memoryId, reviewId);

assertThat(repository.findById(memoryId)).get()
        .satisfies(memory -> {
            assertThat(memory.getValidationStatus()).isEqualTo(MemoryValidationStatus.REFUTED);
            assertThat(memory.isActive()).isFalse();
        });
```

- [ ] **Step 2: Verify tests fail**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchMemoryServiceTests,ResearchMemoryPromotionHarnessTests test
```

Expected: FAIL because research memory types and promotion rules do not exist.

- [ ] **Step 3: Define the three layers**

```java
public record ResearchWorkingMemory(
        Long taskId,
        Long projectId,
        String currentStage,
        String question,
        List<Long> retrievedChunkIds,
        String criticSummary,
        String draftResult
) {
}

public record ResearchMemoryBundle(
        ResearchWorkingMemory working,
        List<String> recentProjectEvents,
        List<ResearchMemoryMatch> longTermMemories
) {
}

public enum MemoryValidationStatus {
    CONFIRMED,
    EXPERIMENT_VERIFIED,
    REFUTED
}

public enum ResearchMemorySourceType {
    DECISION,
    EXPERIMENT_REVIEW
}

public record ValidatedResearchMemory(
        Long projectId,
        ResearchMemorySourceType sourceType,
        Long sourceRecordId,
        MemoryValidationStatus validationStatus,
        String summary,
        List<Long> evidenceChunkIds
) {
    public ValidatedResearchMemory {
        if (validationStatus == MemoryValidationStatus.REFUTED) {
            throw new IllegalArgumentException("Refuted evidence cannot be promoted as active memory");
        }
    }
}
```

Layer ownership:

- Working memory: authoritative source is `ResearchTaskCheckpoint`; Redis caches the latest projection using key prefix `evidencelab:working:`.
- Short-term project memory: Redis sorted set keyed by project, containing recent source, experiment and decision events; database remains authoritative.
- Long-term memory: `ResearchMemoryItem` in MySQL plus Chroma mirror; only confirmed decisions and reviewed experiments are eligible.

- [ ] **Step 4: Implement project-aware recall**

```java
public ResearchMemoryBundle load(Long userId, Long projectId, Long taskId, String query);
public void rememberProjectEvent(Long projectId, ResearchProjectEvent event);
public ResearchMemoryItem rememberValidated(ValidatedResearchMemory memory);
public void markRefuted(Long memoryId, Long reviewId);
```

Recall order is:

1. current task checkpoint;
2. at most 12 recent project events;
3. at most 8 active long-term semantic matches;
4. fallback to the most recently updated active long-term memories when Chroma is unavailable.

`UserMemoryChromaGateway` must be renamed or replaced with a project-scoped gateway whose metadata includes owner, project, memory ID and validation status.

- [ ] **Step 5: Verify recall and invalidation**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchMemoryServiceTests,ResearchMemoryPromotionHarnessTests test
```

Expected: PASS; only the typed validated-memory command can create active long-term memory, refuted memory remains auditable but is excluded from normal recall, and no memory crosses project boundaries.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mindbridge/agent/domain/ResearchMemoryItem.java src/main/java/com/mindbridge/agent/domain/ResearchMemoryType.java src/main/java/com/mindbridge/agent/domain/MemoryValidationStatus.java src/main/java/com/mindbridge/agent/domain/ResearchMemorySourceType.java src/main/java/com/mindbridge/agent/repository/ResearchMemoryItemRepository.java src/main/java/com/mindbridge/agent/service/memory src/test/java/com/mindbridge/agent/service/memory src/test/java/com/mindbridge/agent/harness/ResearchMemoryPromotionHarnessTests.java
git commit -m "feat: add three-layer research memory

AI-Co-Authored-By: Codex"
```

---

### Task 6: Replace the Psychology Loop with the Research Decision Loop

**Files:**
- Modify: `src/main/java/com/mindbridge/agent/domain/IntentType.java`
- Modify: `src/main/java/com/mindbridge/agent/service/agent/AgentName.java`
- Modify: `src/main/java/com/mindbridge/agent/service/agent/AgentAction.java`
- Replace: `src/main/java/com/mindbridge/agent/service/agent/AgentContext.java`
- Replace: `src/main/java/com/mindbridge/agent/service/agent/AgentRunResult.java`
- Modify: `src/main/java/com/mindbridge/agent/service/agent/AgentRuntimeService.java`
- Replace: `src/main/java/com/mindbridge/agent/service/agent/MindBridgeAgent.java` with `ResearchAgent.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/ResearchContextAgent.java`
- Modify: `src/main/java/com/mindbridge/agent/service/agent/SupervisorAgent.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/EvidenceAgent.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/EvidenceCriticAgent.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/ResearchAssistantAgent.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/DecisionAgent.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/IntentClassification.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/EvidenceCritique.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/EvidenceClaim.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/DecisionDraft.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/DecisionOption.java`
- Create: `src/main/java/com/mindbridge/agent/service/agent/AgentContextCheckpoint.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/EvidenceQueryTaskHandler.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/DecisionTaskHandler.java`
- Create: `src/main/java/com/mindbridge/agent/service/task/ResultReviewTaskHandler.java`
- Replace: `src/main/java/com/mindbridge/agent/service/ai/PromptTemplates.java`
- Delete after replacement: `MemoryAgent.java`, `KnowledgeAgent.java`, `RiskGuardianAgent.java`, `CompanionAgent.java`, `CounselorAgent.java`
- Delete: `src/main/java/com/mindbridge/agent/service/PsychologicalAssessmentService.java`
- Delete: `src/main/java/com/mindbridge/agent/service/PsychologyAssessment.java`
- Delete: `src/main/java/com/mindbridge/agent/service/ai/RiskLexicon.java`
- Test: `src/test/java/com/mindbridge/agent/harness/ResearchAgentLoopHarnessTests.java`
- Test helper: `src/test/java/com/mindbridge/agent/harness/ResearchScriptedAiClient.java`

**Interfaces:**
- Consumes: project context, task checkpoints, three-layer memory and project RAG.
- Produces: a structured `AgentRunResult` and one persisted checkpoint per completed stage.

- [ ] **Step 1: Write the four routing-path tests**

```java
assertThat(agentNames(run("解释一下 AdamW", GENERAL_CHAT)))
        .containsExactly(RESEARCH_CONTEXT_AGENT, SUPERVISOR_AGENT, RESEARCH_ASSISTANT_AGENT);

assertThat(agentNames(run("论文中 QLoRA 使用了什么量化配置？", EVIDENCE_QUERY)))
        .containsExactly(RESEARCH_CONTEXT_AGENT, SUPERVISOR_AGENT,
                EVIDENCE_AGENT, RESEARCH_ASSISTANT_AGENT);

assertThat(agentNames(run("12GB 显存该选 LoRA 还是 QLoRA？", RESEARCH_DECISION)))
        .containsExactly(RESEARCH_CONTEXT_AGENT, SUPERVISOR_AGENT,
                EVIDENCE_AGENT, EVIDENCE_CRITIC_AGENT, DECISION_AGENT);

assertThat(agentNames(run("run-019 是否验证了之前的决策？", RESULT_REVIEW)))
        .containsExactly(RESEARCH_CONTEXT_AGENT, SUPERVISOR_AGENT,
                EVIDENCE_AGENT, EVIDENCE_CRITIC_AGENT, DECISION_AGENT);
```

- [ ] **Step 2: Verify old loop cannot satisfy the tests**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchAgentLoopHarnessTests test
```

Expected: FAIL because current intents and psychology agents remain.

- [ ] **Step 3: Replace enums and context**

```java
public enum IntentType {
    GENERAL_CHAT,
    EVIDENCE_QUERY,
    RESEARCH_DECISION,
    RESULT_REVIEW
}

public enum AgentName {
    RESEARCH_CONTEXT_AGENT,
    SUPERVISOR_AGENT,
    EVIDENCE_AGENT,
    EVIDENCE_CRITIC_AGENT,
    RESEARCH_ASSISTANT_AGENT,
    DECISION_AGENT
}

public enum AgentAction {
    LOAD_RESEARCH_CONTEXT,
    ROUTE_INTENT,
    RETRIEVE_EVIDENCE,
    CRITIQUE_EVIDENCE,
    ANSWER_QUERY,
    DRAFT_DECISION,
    REVIEW_RESULT
}
```

`AgentContext` must hold IDs and typed summaries rather than JPA entities for long-running work:

```java
public AgentContext(
        Long taskId,
        Long userId,
        Long projectId,
        String originalInput,
        String modelInput
);
```

It stores `ResearchMemoryBundle`, `IntentType`, retrieved `SearchResult` items, `EvidenceCritique`, `DecisionDraft`, response messages, execution flags and steps.

- [ ] **Step 4: Checkpoint every completed Agent**

```java
public AgentRunResult run(AgentContext context) {
    for (int step = nextStep(context); step <= MAX_STEPS && !context.finished(); step++) {
        researchTaskService.ensureActive(context.taskId());
        ResearchAgent agent = nextAgent(context);
        AgentDecision decision = agent.act(context);
        context.addStep(AgentStep.of(step, agent.name(), decision));
        researchTaskService.saveCheckpoint(
                context.taskId(),
                step,
                agent.name().name(),
                context.currentStage(),
                context.checkpointPayload());
        if (decision.complete()) {
            context.finish();
        }
    }
    return AgentRunResult.from(context);
}
```

`MAX_STEPS` remains 8. Resume reconstructs context from the latest successful checkpoint and continues with the next unsupported flag.

`AgentContextCheckpoint` is a strong record implementing `TaskCheckpointPayload`; it contains the intent, retrieved chunk IDs, EvidenceCritique, DecisionDraft and completed-state flags required to reconstruct `AgentContext`. It must not contain JPA entities or arbitrary maps.

`EvidenceQueryTaskHandler`, `DecisionTaskHandler` and `ResultReviewTaskHandler` each declare exactly one `ResearchTaskType` and delegate to `AgentRuntimeService` with the correct expected intent. Add a registry test asserting all four task types, including `SOURCE_INGESTION`, resolve to exactly one handler.

- [ ] **Step 5: Implement typed model responses**

```java
public record IntentClassification(IntentType intent, double confidence) {
}

public record EvidenceCritique(
        List<EvidenceClaim> supportedClaims,
        List<EvidenceClaim> opposedClaims,
        List<String> evidenceGaps,
        double confidence
) {
}

public record DecisionDraft(
        String question,
        List<DecisionOption> options,
        String recommendation,
        String rationale,
        List<Long> supportingChunkIds,
        List<Long> opposingChunkIds,
        List<String> evidenceGaps,
        String minimumExperiment,
        String successCriteria,
        double confidence
) {
}
```

Prompt constants belong in `PromptTemplates`; repeated JSON field names and fixed labels belong in records/enums rather than scattered string parsing.

- [ ] **Step 6: Run loop tests**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchAgentLoopHarnessTests test
```

Expected: PASS for all four paths; completed stages create checkpoints and resume does not execute them twice.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mindbridge/agent/domain/IntentType.java src/main/java/com/mindbridge/agent/service/agent src/main/java/com/mindbridge/agent/service/ai/PromptTemplates.java src/main/java/com/mindbridge/agent/service/ai/RiskLexicon.java src/main/java/com/mindbridge/agent/service/PsychologicalAssessmentService.java src/main/java/com/mindbridge/agent/service/PsychologyAssessment.java src/test/java/com/mindbridge/agent/harness/ResearchAgentLoopHarnessTests.java src/test/java/com/mindbridge/agent/harness/ResearchScriptedAiClient.java
git commit -m "feat: replace psychology loop with research agents

AI-Co-Authored-By: Codex"
```

---

### Task 7: Add Decision Validation and the Experiment Feedback Loop

**Files:**
- Create: `src/main/java/com/mindbridge/agent/domain/DecisionRecord.java`
- Create: `src/main/java/com/mindbridge/agent/domain/DecisionStatus.java`
- Create: `src/main/java/com/mindbridge/agent/domain/DecisionEvidence.java`
- Create: `src/main/java/com/mindbridge/agent/domain/EvidenceStance.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ExperimentRun.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ExperimentStatus.java`
- Create: `src/main/java/com/mindbridge/agent/domain/DecisionReview.java`
- Create: `src/main/java/com/mindbridge/agent/domain/ReviewVerdict.java`
- Create: `src/main/java/com/mindbridge/agent/repository/DecisionRecordRepository.java`
- Create: `src/main/java/com/mindbridge/agent/repository/DecisionEvidenceRepository.java`
- Create: `src/main/java/com/mindbridge/agent/repository/ExperimentRunRepository.java`
- Create: `src/main/java/com/mindbridge/agent/repository/DecisionReviewRepository.java`
- Create: `src/main/java/com/mindbridge/agent/service/decision/DecisionValidationService.java`
- Create: `src/main/java/com/mindbridge/agent/service/decision/DecisionValidationResult.java`
- Create: `src/main/java/com/mindbridge/agent/service/decision/ValidatedCitation.java`
- Create: `src/main/java/com/mindbridge/agent/service/decision/DecisionService.java`
- Create: `src/main/java/com/mindbridge/agent/service/experiment/ExperimentService.java`
- Create: `src/main/java/com/mindbridge/agent/controller/DecisionController.java`
- Create: `src/main/java/com/mindbridge/agent/controller/ExperimentController.java`
- Create: `src/main/java/com/mindbridge/agent/dto/DecisionResponse.java`
- Create: `src/main/java/com/mindbridge/agent/dto/ConfirmDecisionRequest.java`
- Create: `src/main/java/com/mindbridge/agent/dto/CreateExperimentRequest.java`
- Create: `src/main/java/com/mindbridge/agent/dto/CompleteExperimentRequest.java`
- Create: `src/main/java/com/mindbridge/agent/dto/ExperimentResponse.java`
- Test: `src/test/java/com/mindbridge/agent/service/decision/DecisionValidationServiceTests.java`
- Test: `src/test/java/com/mindbridge/agent/harness/DecisionExperimentLoopHarnessTests.java`

**Interfaces:**
- Consumes: `DecisionDraft`, project-bound `SearchResult`, task and trace.
- Produces: versioned decisions and reviewed experiments that are eligible for long-term memory.

- [ ] **Step 1: Write citation and versioning tests**

```java
assertThatThrownBy(() -> validator.validate(projectAId, draftReferencingProjectBChunk))
        .isInstanceOf(DecisionValidationException.class)
        .hasMessageContaining("citation");

DecisionRecord draft = service.createDraftFromTask(userId, projectId, draftTaskId);
DecisionRecord v1 = service.confirm(userId, projectId, draft.getId());
DecisionRecord v2 = service.regenerate(userId, projectId, v1.getId(), anotherTaskId);

assertThat(v1.getVersion()).isEqualTo(1);
assertThat(v2.getVersion()).isEqualTo(2);
assertThat(v1.getStatus()).isEqualTo(DecisionStatus.CONFIRMED);
assertThat(researchMemoryRepository.findByProject_Id(projectId)).hasSize(1);
```

Before confirmation, assert `researchMemoryRepository.findByProject_Id(projectId)` is empty. `DecisionService.confirm` calls `ResearchLongTermMemoryService.rememberValidated`; draft creation and regeneration do not promote memory.

- [ ] **Step 2: Verify tests fail**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=DecisionValidationServiceTests,DecisionExperimentLoopHarnessTests test
```

Expected: FAIL because decision and experiment domains do not exist.

- [ ] **Step 3: Implement deterministic validation**

```java
public record DecisionValidationResult(
        boolean valid,
        List<String> errors,
        List<ValidatedCitation> supporting,
        List<ValidatedCitation> opposing
) {
}

public DecisionValidationResult validate(
        Long userId,
        Long projectId,
        Long taskId,
        DecisionDraft draft
);
```

Validation rules:

1. Every citation ID exists.
2. Every cited chunk belongs to the current owner and project.
3. Every citation appeared in the current task's retrieved evidence checkpoint.
4. Supporting and opposing citation sets do not contain the same chunk for the same claim.
5. Recommendation, minimum experiment and measurable success criteria are nonblank.
6. Confidence is within 0–1; evidence gaps force confidence below 0.8.
7. A failed validation leaves the task `FAILED` with a safe error and creates no `DecisionRecord`.

- [ ] **Step 4: Implement decision state transitions**

```java
public enum DecisionStatus {
    DRAFT,
    CONFIRMED,
    VALIDATING,
    REVIEWED
}

public enum ReviewVerdict {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    REFUTED,
    INCONCLUSIVE
}
```

Allowed transitions:

```text
DRAFT -> CONFIRMED
CONFIRMED -> VALIDATING
VALIDATING -> REVIEWED
```

`DecisionService` exposes:

```java
public DecisionRecord createDraftFromTask(Long userId, Long projectId, Long taskId);
public DecisionRecord confirm(Long userId, Long projectId, Long decisionId);
public DecisionRecord regenerate(Long userId, Long projectId, Long previousDecisionId, Long taskId);
public DecisionRecord startValidation(Long userId, Long projectId, Long decisionId, Long experimentId);
public DecisionReview confirmReview(Long userId, Long projectId, Long decisionId, Long reviewTaskId);
```

- [ ] **Step 5: Run decision-loop tests**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=DecisionValidationServiceTests,DecisionExperimentLoopHarnessTests test
```

Expected: PASS; foreign or fabricated citations are rejected, confirmed versions remain immutable, and reviewed results promote or invalidate long-term memory correctly.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mindbridge/agent/domain src/main/java/com/mindbridge/agent/repository src/main/java/com/mindbridge/agent/service/decision src/main/java/com/mindbridge/agent/service/experiment src/main/java/com/mindbridge/agent/controller/DecisionController.java src/main/java/com/mindbridge/agent/controller/ExperimentController.java src/test/java/com/mindbridge/agent/service/decision src/test/java/com/mindbridge/agent/harness/DecisionExperimentLoopHarnessTests.java
git commit -m "feat: add validated decision and experiment loop

AI-Co-Authored-By: Codex"
```

Before running this broad `git add`, inspect `git status --short`; replace directory arguments with the exact changed files if unrelated user changes exist.

---

### Task 8: Replace the Chat-First UI with the Decision-First Workspace

**Files:**
- Replace: `src/main/resources/static/index.html`
- Replace: `src/main/resources/static/app.js`
- Replace: `src/main/resources/static/styles.css`
- Modify: `src/main/resources/static/favicon.svg`
- Remove after replacement: `src/main/resources/static/assets/mindbridge-campus-companion.png`
- Replace: `src/main/java/com/mindbridge/agent/controller/ChatController.java` with `ResearchAssistantController.java`
- Modify: `src/main/java/com/mindbridge/agent/controller/AgentStatusController.java`
- Test: `src/test/java/com/mindbridge/agent/harness/ResearchWorkspaceApiHarnessTests.java`

**Interfaces:**
- Consumes: project, source, task, decision, experiment and trace endpoints.
- Produces: the A1 decision-first workspace selected during design.

- [ ] **Step 1: Write API journey tests**

```java
String projectId = createProject();
uploadSource(projectId, "qlora-paper.pdf");
String taskId = createDecisionTask(projectId, "12GB 显存该选 LoRA 还是 QLoRA？");

awaitTask(taskId, "WAITING_FOR_CONFIRMATION");
String decisionId = confirmDecision(projectId, taskId);
String experimentId = createExperiment(projectId, decisionId);
submitExperimentResult(projectId, experimentId);

assertThat(loadWorkspace(projectId))
        .contains(decisionId)
        .contains(experimentId)
        .contains("VALIDATING");
```

- [ ] **Step 2: Verify the journey fails**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchWorkspaceApiHarnessTests test
```

Expected: FAIL because the workspace endpoints and research DTOs are incomplete.

- [ ] **Step 3: Implement the A1 layout**

Primary navigation:

```text
项目概览
决策账本
实验记录
证据库
Agent 轨迹
```

Project overview must render:

- current active decision;
- recommendation and confidence;
- supporting/opposing evidence counts;
- evidence gaps;
- next minimum experiment;
- linked experiment status.

The research assistant opens as a right-side drawer. A normal answer and a formal decision task use separate buttons and endpoints. Do not let a chat response silently create a decision.

Required UI states:

```javascript
const TASK_STATUS = Object.freeze({
  PENDING: "PENDING",
  RUNNING: "RUNNING",
  WAITING_FOR_CONFIRMATION: "WAITING_FOR_CONFIRMATION",
  SUCCEEDED: "SUCCEEDED",
  FAILED: "FAILED",
  CANCELLED: "CANCELLED"
});
```

All repeated status labels, endpoint fragments and visible messages must use constants grouped by responsibility. The page must support loading, empty, error, retry, disconnected SSE and resumed-task states.

- [ ] **Step 4: Verify refresh recovery manually**

Run:

```bash
./scripts/run-dev.sh
```

Manual acceptance:

1. Create a decision task.
2. Refresh while EvidenceCriticAgent is running.
3. Reopen the same project.
4. Confirm the task continues from persisted state.
5. Confirm completed checkpoints are not duplicated.
6. Confirm another project cannot display its sources or decisions.

- [ ] **Step 5: Run API and application tests**

```bash
mvn -Dmaven.repo.local=.m2/repository -Dtest=ResearchWorkspaceApiHarnessTests,AgentApplicationTests test
```

Expected: PASS; the decision-first workspace APIs complete the project-to-review journey.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/index.html src/main/resources/static/app.js src/main/resources/static/styles.css src/main/resources/static/favicon.svg src/main/resources/static/assets/mindbridge-campus-companion.png src/main/java/com/mindbridge/agent/controller/ChatController.java src/main/java/com/mindbridge/agent/controller/ResearchAssistantController.java src/main/java/com/mindbridge/agent/controller/AgentStatusController.java src/test/java/com/mindbridge/agent/harness/ResearchWorkspaceApiHarnessTests.java
git commit -m "feat: add decision-first research workspace

AI-Co-Authored-By: Codex"
```

---

### Task 9: Replace Evaluation Data, Documentation, and Psychology Artifacts

**Files:**
- Replace: `src/main/resources/rag-eval/mindbridge-rag-eval.json` with `evidencelab-rag-eval-v1.json`
- Replace: `src/test/resources/harness/rag-harness-scenarios.json` with `research-harness-scenarios.json`
- Replace: `src/test/java/com/mindbridge/agent/harness/RagEvaluationDatasetTests.java`
- Replace: `src/test/java/com/mindbridge/agent/harness/RagEvaluationHarnessTests.java`
- Delete: `src/test/java/com/mindbridge/agent/harness/SafetyRiskHarnessTests.java`
- Delete: `src/test/java/com/mindbridge/agent/harness/AgentLoopHarnessTests.java`
- Delete: `src/test/java/com/mindbridge/agent/harness/ApiSseHarnessTests.java`
- Replace: `README.md`
- Modify: `.gitignore`
- Replace: `docs/multi-agent-collaboration-copy.md`
- Modify: `docs/rag-technical-guide.md`
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-mysql.yml`
- Modify: `src/main/java/com/mindbridge/agent/config/DataInitializer.java`
- Create: `scripts/run-evidencelab-eval.sh`
- Delete after research fixtures are present:
  - `src/main/resources/knowledge/academic-stress-exam-adjustment.md`
  - `src/main/resources/knowledge/anxiety-grounding-sleep.md`
  - `src/main/resources/knowledge/campus-mental-health.md`
  - `src/main/resources/knowledge/crisis-safety-plan.md`
  - `src/main/resources/knowledge/help-seeking-campus-resources.md`
  - `src/main/resources/knowledge/low-mood-motivation-social-support.md`
  - `src/main/resources/knowledge/privacy-boundaries-consent.md`
  - `src/main/resources/knowledge/relationship-family-conflict.md`
  - `src/main/resources/knowledge/risk-policy.md`
- Test: `src/test/java/com/mindbridge/agent/harness/EvidenceLabAcceptanceTests.java`

**Interfaces:**
- Consumes: all completed tasks.
- Produces: measurable evidence for the four resume bullets and a psychology-free runtime product.

- [ ] **Step 1: Create the versioned evaluation schema**

```java
public record ResearchRagEvalCase(
        String id,
        Long projectId,
        String question,
        IntentType expectedIntent,
        List<String> expectedSources,
        List<String> requiredClaims,
        List<String> opposingClaims,
        List<String> forbiddenClaims
) {
}
```

The initial corpus must cover:

- LoRA versus QLoRA under a 12 GB GPU constraint;
- an OOM experiment log;
- conflicting paper conclusions under different dataset sizes;
- a README describing a baseline configuration;
- two projects containing deliberately similar terms for leakage detection.

- [ ] **Step 2: Add measurable acceptance assertions**

```java
assertThat(metrics.intentAccuracy()).isGreaterThanOrEqualTo(0.90);
assertThat(metrics.recallAtFive()).isGreaterThanOrEqualTo(0.85);
assertThat(metrics.claimSourceSupportRate()).isGreaterThanOrEqualTo(0.95);
assertThat(metrics.structuredOutputSuccessRate()).isGreaterThanOrEqualTo(0.98);
assertThat(metrics.crossProjectLeakageCount()).isZero();
```

Task recovery acceptance must also prove:

- one task survives executor recreation;
- completed checkpoint count remains stable after resume;
- duplicate idempotency keys do not create duplicate tasks;
- task status and result remain queryable after the SSE subscriber disconnects.

- [ ] **Step 3: Remove old product semantics**

Run before deletion:

```bash
rg -n "心理|校园心理|风险等级|自伤|辅导员|Counselor|RiskGuardian|Psychological|MindBridge" README.md docs src pom.xml
```

Replace or remove every runtime occurrence. Historical design documents may retain MindBridge references only when explicitly describing migration history. Rename Maven `artifactId`, `name` and `description` to EvidenceLab without changing the Java package in this phase.

Add `.superpowers/` to `.gitignore` so brainstorming previews do not remain as untracked workspace artifacts.

- [ ] **Step 4: Run the complete verification suite**

```bash
mvn -Dmaven.repo.local=.m2/repository clean test
git diff --check
rg -n "心理|校园心理|风险等级|自伤|辅导员|Counselor|RiskGuardian|Psychological" README.md src pom.xml
```

Expected:

- Maven exits 0.
- `git diff --check` prints nothing.
- The final `rg` command prints nothing.
- Evaluation thresholds pass.
- Cross-project leakage count is zero.

- [ ] **Step 5: Generate benchmark evidence for the resume**

Implement `scripts/run-evidencelab-eval.sh` so it runs the versioned acceptance suite, reads the generated Maven test metrics, obtains the tested commit with `git rev-parse HEAD`, and writes `docs/evaluation/evidencelab-v1-results.md`. The script must exit nonzero if a threshold fails or any metric is missing.

```bash
bash scripts/run-evidencelab-eval.sh --dataset src/main/resources/rag-eval/evidencelab-rag-eval-v1.json --output docs/evaluation/evidencelab-v1-results.md
test -s docs/evaluation/evidencelab-v1-results.md
rg -n "Dataset version|Git commit|Intent accuracy|Recall@5|Claim-source support rate|Structured output success rate|Cross-project leakage count|Task recovery scenarios passed" docs/evaluation/evidencelab-v1-results.md
```

Expected: the script exits 0, the output file is nonempty, and `rg` prints all eight result labels with measured values.

- [ ] **Step 6: Commit**

```bash
git add .gitignore README.md pom.xml docs/multi-agent-collaboration-copy.md docs/rag-technical-guide.md docs/evaluation/evidencelab-v1-results.md scripts/run-evidencelab-eval.sh src/main/java/com/mindbridge/agent/config/DataInitializer.java src/main/resources/application.yml src/main/resources/application-mysql.yml src/main/resources/rag-eval src/main/resources/knowledge src/test/java/com/mindbridge/agent/harness src/test/resources/harness
git commit -m "test: add EvidenceLab end-to-end acceptance

AI-Co-Authored-By: Codex"
```

Inspect `git status --short` before this broad cleanup commit and stage only files belonging to this task.

---

## Four Resume Claims and Their Evidence Gates

The following wording is allowed only after its evidence gate passes.

### 1. Controlled Multi-Agent Orchestration

Resume claim:

> 设计统一 AgentRuntime，围绕上下文装配、意图路由、证据检索、证据审查与决策生成编排六类 Agent，通过共享状态、有限步循环和持久化检查点覆盖资料问答、方案比较、失败诊断与实验复盘。

Evidence gate:

- Four routing paths pass `ResearchAgentLoopHarnessTests`.
- No path exceeds `MAX_STEPS = 8`.
- Each executed Agent creates exactly one successful checkpoint.

### 2. Recoverable Asynchronous Tasks

Resume claim:

> 将论文解析和科研决策从单次 SSE 请求中解耦，设计持久化 ResearchTask 状态机与阶段性 Checkpoint，支持进度查询、失败重试和断线恢复，避免长文档或多阶段 Agent 调用因请求超时丢失结果。

Evidence gate:

- Executor recreation resumes an unfinished task.
- SSE disconnect does not cancel the task.
- Idempotent resubmission does not duplicate work.
- Retry resumes from the last successful checkpoint.

### 3. Three-Layer Research Memory

Resume claim:

> 构建工作记忆、项目短期记忆与长期研究记忆，使用 Redis 管理近期状态，MySQL 持久化决策和实验，结合 Chroma 召回相关经验，并限制只有已确认或实验验证的结论才能进入长期记忆。

Evidence gate:

- Draft decisions cannot be promoted.
- Confirmed decisions and reviewed experiments can be recalled.
- Refuted memories remain auditable but are excluded from active recall.
- Project isolation tests report zero leakage.

### 4. Long-Document Project-Level RAG

Resume claim:

> 将 PDF、Markdown 和实验日志解析为带页码、章节及文本位置的结构化片段，融合 Chroma 向量检索与 BM25 召回，引入查询改写、重排和二次检索，并通过 projectId 过滤保证跨项目数据隔离。

Evidence gate:

- PDF source citations resolve to real page numbers.
- Markdown citations resolve to real headings.
- TXT/log citations resolve to stable offsets.
- Recall@5 reaches the specified threshold.
- Chroma and local fallback both pass zero-leakage tests.

Do not add unsupported numbers such as “supports 200,000 characters” or “improves efficiency by 60%” until a reproducible benchmark records those results.

## Recommended Execution Order

```text
Project boundary
→ Position-aware parsing
→ Project-isolated RAG
→ Recoverable task runtime
→ Three-layer memory
→ Research Agent loop
→ Decision validation and experiment feedback
→ Decision-first workspace
→ Evaluation and old-domain cleanup
```

Each task produces independently testable software. Do not start UI replacement before the API journey and task lifecycle are stable. Do not delete psychology classes until equivalent EvidenceLab paths and tests exist.
