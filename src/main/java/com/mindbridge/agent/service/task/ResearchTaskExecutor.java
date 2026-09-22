package com.mindbridge.agent.service.task;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
/**
 * 异步任务执行器。与 SSE 解耦：提交后立即返回，重启时恢复未完成任务。
 */
public class ResearchTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResearchTaskExecutor.class);
    static final String HANDLER_ERROR_CODE = "HANDLER_FAILURE";

    private final ResearchTaskService researchTaskService;
    private final ResearchTaskHandlerRegistry handlerRegistry;
    private final MindBridgeProperties properties;
    private final TaskExecutor taskExecutor;
    private final Set<Long> activeTaskIds = ConcurrentHashMap.newKeySet();

    public ResearchTaskExecutor(
            ResearchTaskService researchTaskService,
            ResearchTaskHandlerRegistry handlerRegistry,
            MindBridgeProperties properties,
            @Qualifier("researchTaskWorkerExecutor") TaskExecutor taskExecutor
    ) {
        this.researchTaskService = researchTaskService;
        this.handlerRegistry = handlerRegistry;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    public void submit(Long taskId) {
        taskExecutor.execute(() -> execute(taskId));
    }

    public void execute(Long taskId) {
        if (!researchTaskService.claimPendingTask(taskId)) {
            return;
        }
        activeTaskIds.add(taskId);
        try {
            researchTaskService.ensureActive(taskId);
            ResearchTask task = researchTaskService.getRequired(taskId);
            TaskExecutionResult result = handlerRegistry.require(task.getType())
                    .execute(task, researchTaskService.latestCheckpoint(taskId));
            applyResult(taskId, result);
        } catch (TransientTaskException exception) {
            handleTransient(taskId, exception);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            researchTaskService.markFailed(taskId, HANDLER_ERROR_CODE, exception.getMessage());
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            researchTaskService.markFailed(taskId, HANDLER_ERROR_CODE, message);
        } finally {
            activeTaskIds.remove(taskId);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeIncompleteTasks() {
        recoverIncompleteTasks();
    }

    @Scheduled(fixedDelayString = "${mindbridge.task.recovery-interval:PT30S}")
    public void recoverIncompleteTasks() {
        researchTaskService.heartbeatRunning(activeTaskIds);
        Instant staleBefore = Instant.now().minus(properties.getTask().getStaleRunningAfter());
        int reset = researchTaskService.resetStaleRunning(staleBefore);
        if (reset > 0) {
            log.info("Reset {} stale RUNNING research tasks to PENDING", reset);
        }
        List<ResearchTask> ready = researchTaskService.findPending().stream()
                .filter(this::readyForRetry)
                .toList();
        for (ResearchTask task : ready) {
            submit(task.getId());
        }
        if (!ready.isEmpty()) {
            log.info("Resumed {} PENDING research tasks", ready.size());
        }
    }

    private void applyResult(Long taskId, TaskExecutionResult result) {
        if (result == null || result.status() == null) {
            researchTaskService.markFailed(taskId, HANDLER_ERROR_CODE, "Handler returned empty result");
            return;
        }
        switch (result.status()) {
            case SUCCEEDED -> researchTaskService.markSucceeded(taskId, result.resultReferenceId());
            case WAITING_FOR_CONFIRMATION -> researchTaskService.markWaitingForConfirmation(
                    taskId, result.resultReferenceId());
            case FAILED -> researchTaskService.markFailed(taskId, HANDLER_ERROR_CODE, "Handler reported failure");
            case CANCELLED -> {
                // cooperative cancel already persisted
            }
            default -> researchTaskService.markFailed(
                    taskId, HANDLER_ERROR_CODE, "Unexpected handler status " + result.status());
        }
    }

    private void handleTransient(Long taskId, TransientTaskException exception) {
        ResearchTask task = researchTaskService.getRequired(taskId);
        if (task.getStatus() == ResearchTaskStatus.CANCELLED) {
            return;
        }
        int maxAttempts = Math.max(1, properties.getTask().getMaxAttempts());
        if (task.getAttemptCount() < maxAttempts) {
            researchTaskService.requeueAfterTransient(taskId, exception.getMessage());
            return;
        }
        researchTaskService.markFailed(taskId, ResearchTaskService.TRANSIENT_ERROR_CODE, exception.getMessage());
    }

    private boolean readyForRetry(ResearchTask task) {
        if (!ResearchTaskService.TRANSIENT_ERROR_CODE.equals(task.getErrorCode())) {
            return true;
        }
        Duration initialDelay = properties.getTask().getRetryInitialDelay();
        long initialMillis = Math.max(0, initialDelay.toMillis());
        int exponent = Math.min(Math.max(0, task.getAttemptCount() - 1), 10);
        long delayMillis = Math.min(30_000L, initialMillis * (1L << exponent));
        return !task.getUpdatedAt().plusMillis(delayMillis).isAfter(Instant.now());
    }
}
