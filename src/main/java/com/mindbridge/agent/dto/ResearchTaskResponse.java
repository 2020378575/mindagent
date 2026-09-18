package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import java.time.Instant;

public record ResearchTaskResponse(
        Long id,
        String publicId,
        ResearchTaskType type,
        ResearchTaskStatus status,
        ResearchTaskStage currentStage,
        int progressPercent,
        String question,
        Long sourceId,
        Long decisionId,
        Long experimentId,
        Long resultReferenceId,
        String errorCode,
        String errorMessage,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt
) {
    public static ResearchTaskResponse from(ResearchTask task) {
        return new ResearchTaskResponse(
                task.getId(),
                task.getPublicId(),
                task.getType(),
                task.getStatus(),
                task.getCurrentStage(),
                task.getProgressPercent(),
                task.getQuestion(),
                task.getSourceId(),
                task.getDecisionId(),
                task.getExperimentId(),
                task.getResultReferenceId(),
                task.getErrorCode(),
                task.getErrorMessage(),
                task.getAttemptCount(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getStartedAt(),
                task.getCompletedAt()
        );
    }
}
