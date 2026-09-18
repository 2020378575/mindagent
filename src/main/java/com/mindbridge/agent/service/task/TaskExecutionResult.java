package com.mindbridge.agent.service.task;

import com.mindbridge.agent.domain.ResearchTaskStatus;

/**
 * Handler 执行结果。由 Executor 根据 status 调用 markSucceeded / markWaiting / markFailed。
 */
public record TaskExecutionResult(
        ResearchTaskStatus status,
        Long resultReferenceId
) {
}
