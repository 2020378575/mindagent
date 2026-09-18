package com.mindbridge.agent.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.dto.CreateResearchTaskRequest;
import com.mindbridge.agent.dto.ResearchTaskEvent;
import com.mindbridge.agent.repository.ResearchTaskCheckpointRepository;
import com.mindbridge.agent.repository.ResearchTaskRepository;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 研究任务状态机。只改状态和检查点，不直接跑 Handler。
 */
public class ResearchTaskService {

    static final String TASK_NOT_FOUND_MESSAGE = "Research task not found";
    static final String TASK_CANCELLED_MESSAGE = "Research task cancelled";
    static final String TASK_NOT_RETRYABLE_MESSAGE = "Research task cannot be retried";
    private static final int MAX_ERROR_MESSAGE = 500;

    private final ResearchProjectService researchProjectService;
    private final ResearchTaskRepository researchTaskRepository;
    private final ResearchTaskCheckpointRepository checkpointRepository;
    private final ResearchTaskEventService eventService;
    private final ObjectMapper objectMapper;

    public ResearchTaskService(
            ResearchProjectService researchProjectService,
            ResearchTaskRepository researchTaskRepository,
            ResearchTaskCheckpointRepository checkpointRepository,
            ResearchTaskEventService eventService,
            ObjectMapper objectMapper
    ) {
        this.researchProjectService = researchProjectService;
        this.researchTaskRepository = researchTaskRepository;
        this.checkpointRepository = checkpointRepository;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ResearchTask create(Long userId, Long projectId, CreateResearchTaskRequest request) {
        ResearchProject project = researchProjectService.requireOwnedProject(userId, projectId);
        Optional<ResearchTask> existing = researchTaskRepository.findByOwner_IdAndProject_IdAndIdempotencyKey(
                userId, projectId, request.idempotencyKey().trim());
        if (existing.isPresent()) {
            return existing.get();
        }
        ResearchTask task = new ResearchTask();
        task.setPublicId(UUID.randomUUID().toString());
        task.setIdempotencyKey(request.idempotencyKey().trim());
        task.setProject(project);
        task.setOwner(project.getOwner());
        task.setType(request.type());
        task.setStatus(ResearchTaskStatus.PENDING);
        task.setQuestion(blankToNull(request.question()));
        task.setSourceId(request.sourceId());
        task.setDecisionId(request.decisionId());
        task.setExperimentId(request.experimentId());
        task.setProgressPercent(0);
        task.setAttemptCount(0);
        ResearchTask saved = researchTaskRepository.save(task);
        publish(saved, "Task created");
        return saved;
    }

    @Transactional(readOnly = true)
    public ResearchTask requireOwnedTask(Long userId, Long projectId, String taskPublicId) {
        researchProjectService.requireOwnedProject(userId, projectId);
        return researchTaskRepository.findByPublicIdAndProject_IdAndOwner_Id(taskPublicId, projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND_MESSAGE));
    }

    @Transactional
    public boolean claimPendingTask(Long taskId) {
        return researchTaskRepository.claimPendingTask(taskId, Instant.now()) == 1;
    }

    @Transactional
    public void saveCheckpoint(
            Long taskId,
            int stepNumber,
            String actor,
            ResearchTaskStage stage,
            TaskCheckpointPayload payload
    ) {
        ResearchTask task = requiredTask(taskId);
        ensureActive(task);
        ResearchTaskCheckpoint checkpoint = new ResearchTaskCheckpoint();
        checkpoint.setTask(task);
        checkpoint.setStepNumber(stepNumber);
        checkpoint.setActor(actor);
        checkpoint.setStage(stage);
        checkpoint.setStatus(ResearchTaskStatus.SUCCEEDED);
        checkpoint.setResultJson(writePayload(payload));
        checkpoint.setObservation(stage.name() + " completed");
        checkpoint.setStartedAt(Instant.now());
        checkpoint.setCompletedAt(Instant.now());
        checkpointRepository.save(checkpoint);

        task.setCurrentStage(stage);
        task.setProgressPercent(progressFor(stage));
        task.touch();
        researchTaskRepository.save(task);
        publish(task, checkpoint.getObservation());
    }

    @Transactional
    public void markWaitingForConfirmation(Long taskId, Long resultReferenceId) {
        ResearchTask task = requiredTask(taskId);
        task.setStatus(ResearchTaskStatus.WAITING_FOR_CONFIRMATION);
        task.setResultReferenceId(resultReferenceId);
        task.setProgressPercent(Math.max(task.getProgressPercent(), 90));
        task.setCompletedAt(Instant.now());
        researchTaskRepository.save(task);
        publish(task, "Waiting for confirmation");
    }

    @Transactional
    public void markSucceeded(Long taskId, Long resultReferenceId) {
        ResearchTask task = requiredTask(taskId);
        task.setStatus(ResearchTaskStatus.SUCCEEDED);
        task.setResultReferenceId(resultReferenceId);
        task.setProgressPercent(100);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setCompletedAt(Instant.now());
        researchTaskRepository.save(task);
        publish(task, "Task succeeded");
    }

    @Transactional
    public void markFailed(Long taskId, String errorCode, String safeMessage) {
        ResearchTask task = requiredTask(taskId);
        task.setStatus(ResearchTaskStatus.FAILED);
        task.setErrorCode(errorCode);
        task.setErrorMessage(trimMessage(safeMessage));
        task.setCompletedAt(Instant.now());
        researchTaskRepository.save(task);
        publish(task, task.getErrorMessage());
    }

    @Transactional
    public ResearchTask retry(Long userId, Long projectId, String taskPublicId) {
        ResearchTask task = requireOwnedTask(userId, projectId, taskPublicId);
        if (task.getStatus() != ResearchTaskStatus.FAILED) {
            throw new IllegalArgumentException(TASK_NOT_RETRYABLE_MESSAGE);
        }
        return requeue(task, "Task queued for retry");
    }

    @Transactional
    public ResearchTask requeueAfterTransient(Long taskId, String safeMessage) {
        ResearchTask task = requiredTask(taskId);
        task.setErrorCode("TRANSIENT_FAILURE");
        task.setErrorMessage(trimMessage(safeMessage));
        return requeue(task, "Transient failure, requeued");
    }

    private ResearchTask requeue(ResearchTask task, String message) {
        task.setStatus(ResearchTaskStatus.PENDING);
        task.setCompletedAt(null);
        task.touch();
        ResearchTask saved = researchTaskRepository.save(task);
        publish(saved, message);
        return saved;
    }

    @Transactional
    public ResearchTask cancel(Long userId, Long projectId, String taskPublicId) {
        ResearchTask task = requireOwnedTask(userId, projectId, taskPublicId);
        if (task.getStatus() == ResearchTaskStatus.SUCCEEDED
                || task.getStatus() == ResearchTaskStatus.CANCELLED) {
            return task;
        }
        task.setStatus(ResearchTaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        ResearchTask saved = researchTaskRepository.save(task);
        publish(saved, "Task cancelled");
        return saved;
    }

    @Transactional(readOnly = true)
    public void ensureActive(Long taskId) {
        ensureActive(requiredTask(taskId));
    }

    @Transactional(readOnly = true)
    public Optional<ResearchTaskCheckpoint> latestCheckpoint(Long taskId) {
        return checkpointRepository.findFirstByTask_IdOrderByStepNumberDesc(taskId);
    }

    @Transactional(readOnly = true)
    public List<ResearchTaskCheckpoint> checkpoints(Long taskId) {
        return checkpointRepository.findByTask_IdOrderByStepNumberAsc(taskId);
    }

    @Transactional(readOnly = true)
    public ResearchTask getRequired(Long taskId) {
        return requiredTask(taskId);
    }

    @Transactional
    public int resetStaleRunning(Instant staleBefore) {
        return researchTaskRepository.resetStaleRunningTasks(staleBefore, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<ResearchTask> findPending() {
        return researchTaskRepository.findByStatus(ResearchTaskStatus.PENDING);
    }

    private void ensureActive(ResearchTask task) {
        if (task.getStatus() == ResearchTaskStatus.CANCELLED) {
            throw new IllegalStateException(TASK_CANCELLED_MESSAGE);
        }
    }

    private ResearchTask requiredTask(Long taskId) {
        return researchTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException(TASK_NOT_FOUND_MESSAGE));
    }

    private void publish(ResearchTask task, String message) {
        eventService.publish(new ResearchTaskEvent(
                task.getPublicId(),
                task.getStatus(),
                task.getCurrentStage(),
                task.getProgressPercent(),
                message,
                Instant.now()
        ));
    }

    private String writePayload(TaskCheckpointPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize checkpoint payload", exception);
        }
    }

    private int progressFor(ResearchTaskStage stage) {
        return switch (stage) {
            case SOURCE_STORAGE -> 15;
            case PARSING -> 45;
            case INDEXING -> 80;
            case CONTEXT -> 30;
            case EVIDENCE -> 55;
            case CRITIC -> 70;
            case DECISION -> 85;
            case REVIEW -> 95;
        };
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String trimMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Task failed";
        }
        return message.length() > MAX_ERROR_MESSAGE ? message.substring(0, MAX_ERROR_MESSAGE) : message;
    }
}
