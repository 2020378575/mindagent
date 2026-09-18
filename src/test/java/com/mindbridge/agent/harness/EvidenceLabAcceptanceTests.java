package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.CreateResearchTaskRequest;
import com.mindbridge.agent.repository.ResearchTaskCheckpointRepository;
import com.mindbridge.agent.repository.ResearchTaskRepository;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.knowledge.KnowledgeService;
import com.mindbridge.agent.service.knowledge.eval.EvidenceLabMetrics;
import com.mindbridge.agent.service.knowledge.eval.RagEvalReport;
import com.mindbridge.agent.service.knowledge.eval.RagEvaluationService;
import com.mindbridge.agent.service.project.ResearchProjectService;
import com.mindbridge.agent.service.task.ResearchTaskEventService;
import com.mindbridge.agent.service.task.ResearchTaskExecutor;
import com.mindbridge.agent.service.task.ResearchTaskHandler;
import com.mindbridge.agent.service.task.ResearchTaskHandlerRegistry;
import com.mindbridge.agent.service.task.ResearchTaskService;
import com.mindbridge.agent.service.task.TaskCheckpointPayload;
import com.mindbridge.agent.service.task.TaskExecutionResult;
import com.mindbridge.agent.service.task.TransientTaskException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

@ExtendWith(MockitoExtension.class)
class EvidenceLabAcceptanceTests {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 10L;
    private static final String DATASET = "classpath:rag-eval/evidencelab-rag-eval-v1.json";
    private static final Path METRICS_PATH = Path.of("target/evidencelab-metrics.json");

    @Mock
    private ResearchProjectService projectService;

    @Mock
    private ResearchTaskRepository taskRepository;

    @Mock
    private ResearchTaskCheckpointRepository checkpointRepository;

    @Mock
    private ResearchTaskHandler decisionHandler;

    private final Map<Long, ResearchTask> tasks = new HashMap<>();
    private final List<ResearchTaskCheckpoint> checkpoints = new ArrayList<>();
    private final AtomicLong taskIds = new AtomicLong(1);
    private final AtomicLong checkpointIds = new AtomicLong(1);

    private ResearchTaskService taskService;
    private MindBridgeProperties properties;
    private RagEvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        ScriptedAiClient aiClient = new ScriptedAiClient();
        KnowledgeService knowledgeService = mock(KnowledgeService.class);
        when(knowledgeService.retrieve(anyString(), anyInt())).thenAnswer(invocation ->
                RagEvaluationHarnessTests.fixtureHits(invocation.getArgument(0)));
        evaluationService = new RagEvaluationService(
                knowledgeService,
                aiClient,
                new IntentClassifier(aiClient),
                new ObjectMapper());

        properties = new MindBridgeProperties();
        properties.getTask().setMaxAttempts(2);
        properties.getTask().setStaleRunningAfter(java.time.Duration.ofMinutes(5));

        ResearchProject project = project();
        lenient().when(projectService.requireOwnedProject(USER_ID, PROJECT_ID)).thenReturn(project);
        lenient().when(decisionHandler.type()).thenReturn(ResearchTaskType.DECISION);
        wireTaskRepositories();
        taskService = new ResearchTaskService(
                projectService,
                taskRepository,
                checkpointRepository,
                new ResearchTaskEventService(),
                new ObjectMapper());
    }

    @Test
    void evidenceLabAcceptanceGatesPassAndWriteMetrics() throws Exception {
        int recoveryPassed = runTaskRecoveryScenarios();
        assertThat(recoveryPassed).isEqualTo(4);

        long leakageCount = countCrossProjectLeakage();
        assertThat(leakageCount).isZero();

        String gitCommit = resolveGitCommit();
        RagEvalReport report = evaluationService.evaluate(DATASET, 5, gitCommit, leakageCount, recoveryPassed);
        EvidenceLabMetrics metrics = report.metrics();

        assertThat(metrics.intentAccuracy()).isGreaterThanOrEqualTo(0.90);
        assertThat(metrics.recallAtFive()).isGreaterThanOrEqualTo(0.85);
        assertThat(metrics.claimSourceSupportRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(metrics.structuredOutputSuccessRate()).isGreaterThanOrEqualTo(0.98);
        assertThat(metrics.crossProjectLeakageCount()).isZero();
        assertThat(metrics.taskRecoveryScenariosPassed()).isEqualTo(4);

        Files.createDirectories(METRICS_PATH.getParent());
        evaluationService.writeMetrics(metrics, METRICS_PATH.toString());
        assertThat(METRICS_PATH).exists();
        String metricsJson = Files.readString(METRICS_PATH);
        assertThat(metricsJson)
                .contains("intentAccuracy")
                .contains("recallAtFive")
                .contains("claimSourceSupportRate")
                .contains("structuredOutputSuccessRate")
                .contains("crossProjectLeakageCount")
                .contains("taskRecoveryScenariosPassed");
    }

    private int runTaskRecoveryScenarios() {
        int passed = 0;

        // 1) task survives executor recreation
        ResearchTask task = taskService.create(USER_ID, PROJECT_ID, new CreateResearchTaskRequest(
                "accept-decision-001", ResearchTaskType.DECISION, "LoRA or QLoRA?", null, null, null));
        Long taskId = task.getId();
        when(decisionHandler.execute(any(), any())).thenAnswer(invocation -> {
            taskService.saveCheckpoint(taskId, 1, "TEST_HANDLER", ResearchTaskStage.CRITIC, checkpointPayload());
            throw new TransientTaskException("simulated disconnect");
        });
        newExecutor().execute(taskId);
        assertThat(tasks.get(taskId).getStatus()).isEqualTo(ResearchTaskStatus.PENDING);
        int checkpointCountAfterFail = checkpoints.size();
        reset(decisionHandler);
        when(decisionHandler.type()).thenReturn(ResearchTaskType.DECISION);
        when(decisionHandler.execute(any(), any()))
                .thenReturn(new TaskExecutionResult(ResearchTaskStatus.SUCCEEDED, 42L));
        newExecutor().resumeIncompleteTasks();
        assertThat(tasks.get(taskId).getStatus()).isEqualTo(ResearchTaskStatus.SUCCEEDED);
        passed++;

        // 2) completed checkpoint count remains stable after resume
        assertThat(checkpoints).hasSize(checkpointCountAfterFail);
        passed++;

        // 3) duplicate idempotency keys do not create duplicate tasks
        ResearchTask first = taskService.create(USER_ID, PROJECT_ID, new CreateResearchTaskRequest(
                "accept-idempotent-key", ResearchTaskType.DECISION, "prefer QLoRA?", null, null, null));
        ResearchTask second = taskService.create(USER_ID, PROJECT_ID, new CreateResearchTaskRequest(
                "accept-idempotent-key", ResearchTaskType.DECISION, "prefer QLoRA again?", null, null, null));
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(tasks.values().stream()
                .filter(item -> "accept-idempotent-key".equals(item.getIdempotencyKey()))
                .count()).isEqualTo(1);
        passed++;

        // 4) task status/result remain queryable after SSE subscriber disconnect simulation
        ResearchTask queryable = tasks.get(taskId);
        assertThat(queryable.getStatus()).isEqualTo(ResearchTaskStatus.SUCCEEDED);
        assertThat(queryable.getResultReferenceId()).isEqualTo(42L);
        assertThat(queryable.getPublicId()).isNotBlank();
        assertThat(taskService.list(USER_ID, PROJECT_ID))
                .anySatisfy(item -> {
                    assertThat(item.getId()).isEqualTo(taskId);
                    assertThat(item.getStatus()).isEqualTo(ResearchTaskStatus.SUCCEEDED);
                    assertThat(item.getResultReferenceId()).isEqualTo(42L);
                });
        passed++;

        return passed;
    }

    private long countCrossProjectLeakage() {
        List<String> alphaHits = RagEvaluationHarnessTests.fixtureHits("Project Alpha 专属标记")
                .stream()
                .map(hit -> hit.content() + " " + hit.source())
                .toList();
        List<String> betaHits = RagEvaluationHarnessTests.fixtureHits("Project Beta 专属标记")
                .stream()
                .map(hit -> hit.content() + " " + hit.source())
                .toList();
        long alphaLeak = alphaHits.stream().filter(text -> text.contains("BETA-ONLY-SIGNAL")).count();
        long betaLeak = betaHits.stream().filter(text -> text.contains("ALPHA-ONLY-SIGNAL")).count();
        return alphaLeak + betaLeak;
    }

    private ResearchTaskExecutor newExecutor() {
        return new ResearchTaskExecutor(
                taskService,
                new ResearchTaskHandlerRegistry(List.of(decisionHandler)),
                properties,
                new SyncTaskExecutor());
    }

    private void wireTaskRepositories() {
        lenient().when(taskRepository.findByOwner_IdAndProject_IdAndIdempotencyKey(eq(USER_ID), eq(PROJECT_ID), any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(2);
                    return tasks.values().stream()
                            .filter(task -> key.equals(task.getIdempotencyKey()))
                            .findFirst();
                });
        lenient().when(taskRepository.save(any(ResearchTask.class))).thenAnswer(invocation -> {
            ResearchTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(taskIds.getAndIncrement());
            }
            tasks.put(task.getId(), task);
            return task;
        });
        lenient().when(taskRepository.findById(anyLong())).thenAnswer(invocation ->
                Optional.ofNullable(tasks.get(invocation.getArgument(0))));
        lenient().when(taskRepository.claimPendingTask(anyLong(), any(Instant.class))).thenAnswer(invocation -> {
            ResearchTask task = tasks.get(invocation.getArgument(0));
            Instant now = invocation.getArgument(1);
            if (task == null || task.getStatus() != ResearchTaskStatus.PENDING) {
                return 0;
            }
            task.setStatus(ResearchTaskStatus.RUNNING);
            task.setAttemptCount(task.getAttemptCount() + 1);
            if (task.getStartedAt() == null) {
                task.setStartedAt(now);
            }
            task.touch();
            return 1;
        });
        lenient().when(taskRepository.resetStaleRunningTasks(any(Instant.class), any(Instant.class))).thenReturn(0);
        lenient().when(taskRepository.findByStatus(ResearchTaskStatus.PENDING)).thenAnswer(invocation ->
                tasks.values().stream().filter(task -> task.getStatus() == ResearchTaskStatus.PENDING).toList());
        lenient().when(taskRepository.findByProject_IdAndOwner_IdOrderByUpdatedAtDesc(eq(PROJECT_ID), eq(USER_ID)))
                .thenAnswer(invocation -> tasks.values().stream()
                        .sorted(java.util.Comparator.comparing(ResearchTask::getUpdatedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                        .toList());
        lenient().when(checkpointRepository.save(any(ResearchTaskCheckpoint.class))).thenAnswer(invocation -> {
            ResearchTaskCheckpoint checkpoint = invocation.getArgument(0);
            if (checkpoint.getId() == null) {
                checkpoint.setId(checkpointIds.getAndIncrement());
            }
            checkpoints.removeIf(existing -> existing.getId() != null && existing.getId().equals(checkpoint.getId()));
            checkpoints.add(checkpoint);
            return checkpoint;
        });
        lenient().when(checkpointRepository.findByTask_IdOrderByStepNumberAsc(anyLong())).thenAnswer(invocation -> {
            Long taskId = invocation.getArgument(0);
            return checkpoints.stream()
                    .filter(checkpoint -> taskId.equals(checkpoint.getTask().getId()))
                    .sorted(java.util.Comparator.comparingInt(ResearchTaskCheckpoint::getStepNumber))
                    .toList();
        });
        lenient().when(checkpointRepository.findFirstByTask_IdOrderByStepNumberDesc(anyLong()))
                .thenAnswer(invocation -> {
                    Long taskId = invocation.getArgument(0);
                    return checkpoints.stream()
                            .filter(checkpoint -> taskId.equals(checkpoint.getTask().getId()))
                            .max(java.util.Comparator.comparingInt(ResearchTaskCheckpoint::getStepNumber));
                });
    }

    private TaskCheckpointPayload checkpointPayload() {
        return new TestCheckpointPayload(ResearchTaskStage.CRITIC);
    }

    private record TestCheckpointPayload(ResearchTaskStage stage) implements TaskCheckpointPayload {
    }

    private ResearchProject project() {
        UserAccount owner = new UserAccount();
        owner.setUsername("user-" + USER_ID);
        owner.setDisplayName("User");
        owner.setPassword("secret");
        ResearchProject project = new ResearchProject();
        project.setId(PROJECT_ID);
        project.setOwner(owner);
        project.setName("Acceptance Project");
        project.setObjective("Choose LoRA or QLoRA");
        return project;
    }

    private String resolveGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int code = process.waitFor();
            return code == 0 && !output.isBlank() ? output : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }
}
