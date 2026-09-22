package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
import com.mindbridge.agent.service.project.ResearchProjectService;
import com.mindbridge.agent.service.task.ResearchTaskEventService;
import com.mindbridge.agent.service.task.ResearchTaskExecutor;
import com.mindbridge.agent.service.task.ResearchTaskHandler;
import com.mindbridge.agent.service.task.ResearchTaskHandlerRegistry;
import com.mindbridge.agent.service.task.ResearchTaskService;
import com.mindbridge.agent.service.task.TaskCheckpointPayload;
import com.mindbridge.agent.service.task.TaskExecutionResult;
import com.mindbridge.agent.service.task.TransientTaskException;
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
class ResearchTaskRecoveryHarnessTests {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 10L;

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

    @BeforeEach
    void setUp() {
        properties = new MindBridgeProperties();
        properties.getTask().setMaxAttempts(2);
        properties.getTask().setStaleRunningAfter(java.time.Duration.ofMinutes(5));
        properties.getTask().setRetryInitialDelay(java.time.Duration.ZERO);

        ResearchProject project = project();
        lenient().when(projectService.requireOwnedProject(USER_ID, PROJECT_ID)).thenReturn(project);
        lenient().when(decisionHandler.type()).thenReturn(ResearchTaskType.DECISION);

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

        taskService = new ResearchTaskService(
                projectService,
                taskRepository,
                checkpointRepository,
                new ResearchTaskEventService(),
                new ObjectMapper());
    }

    @Test
    void recoveryTickResumesAfterLastCheckpointWithoutRestart() {
        ResearchTask task = taskService.create(USER_ID, PROJECT_ID, new CreateResearchTaskRequest(
                "decision-001", ResearchTaskType.DECISION, "LoRA or QLoRA?", null, null, null));
        Long taskId = task.getId();

        when(decisionHandler.execute(any(), any())).thenAnswer(invocation -> {
            taskService.saveCheckpoint(
                    taskId,
                    1,
                    "TEST_DECISION_HANDLER",
                    ResearchTaskStage.CRITIC,
                    criticCheckpoint());
            throw new TransientTaskException("simulated model disconnect");
        });

        ResearchTaskExecutor firstExecutor = newExecutorUsingSameRepositories(decisionHandler);
        firstExecutor.execute(taskId);

        assertThat(tasks.get(taskId).getStatus()).isEqualTo(ResearchTaskStatus.PENDING);
        assertThat(checkpoints)
                .extracting(ResearchTaskCheckpoint::getStage)
                .contains(ResearchTaskStage.CRITIC);

        reset(decisionHandler);
        when(decisionHandler.execute(any(), any()))
                .thenReturn(new TaskExecutionResult(ResearchTaskStatus.SUCCEEDED, 99L));

        firstExecutor.recoverIncompleteTasks();

        assertThat(tasks.get(taskId).getStatus()).isEqualTo(ResearchTaskStatus.SUCCEEDED);
        assertThat(tasks.get(taskId).getResultReferenceId()).isEqualTo(99L);
        assertThat(checkpoints)
                .extracting(ResearchTaskCheckpoint::getStage)
                .contains(ResearchTaskStage.CRITIC);
    }

    @Test
    void recoveryTickRespectsBackoffBeforeRetrying() {
        properties.getTask().setRetryInitialDelay(java.time.Duration.ofHours(1));
        ResearchTask task = taskService.create(USER_ID, PROJECT_ID, new CreateResearchTaskRequest(
                "backoff-001", ResearchTaskType.DECISION, "Retry?", null, null, null));
        when(decisionHandler.execute(any(), any())).thenThrow(new TransientTaskException("temporary"));
        ResearchTaskExecutor executor = newExecutorUsingSameRepositories(decisionHandler);
        executor.execute(task.getId());

        reset(decisionHandler);
        when(decisionHandler.execute(any(), any()))
                .thenReturn(new TaskExecutionResult(ResearchTaskStatus.SUCCEEDED, 99L));
        executor.recoverIncompleteTasks();
        assertThat(tasks.get(task.getId()).getStatus()).isEqualTo(ResearchTaskStatus.PENDING);

        properties.getTask().setRetryInitialDelay(java.time.Duration.ZERO);
        executor.recoverIncompleteTasks();
        assertThat(tasks.get(task.getId()).getStatus()).isEqualTo(ResearchTaskStatus.SUCCEEDED);
    }

    private ResearchTaskExecutor newExecutorUsingSameRepositories(ResearchTaskHandler handler) {
        return new ResearchTaskExecutor(
                taskService,
                new ResearchTaskHandlerRegistry(List.of(handler)),
                properties,
                new SyncTaskExecutor());
    }

    private TaskCheckpointPayload criticCheckpoint() {
        return new TestCheckpointPayload(ResearchTaskStage.CRITIC);
    }

    private ResearchProject project() {
        UserAccount owner = new UserAccount();
        owner.setUsername("user-" + USER_ID);
        owner.setDisplayName("User");
        owner.setPassword("secret");
        ResearchProject project = new ResearchProject();
        project.setId(PROJECT_ID);
        project.setOwner(owner);
        project.setName("Adapters");
        project.setObjective("Choose LoRA or QLoRA");
        return project;
    }

    private record TestCheckpointPayload(ResearchTaskStage stage) implements TaskCheckpointPayload {
    }
}
