package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.ExperimentRun;
import com.mindbridge.agent.domain.ExperimentStatus;
import java.time.Instant;

public record ExperimentResponse(
        Long id,
        Long projectId,
        Long decisionId,
        ExperimentStatus status,
        String title,
        String hypothesis,
        String setupNotes,
        String metricsJson,
        String resultSummary,
        Instant createdAt,
        Instant completedAt
) {
    public static ExperimentResponse from(ExperimentRun run) {
        return new ExperimentResponse(
                run.getId(),
                run.projectId(),
                run.getDecisionId(),
                run.getStatus(),
                run.getTitle(),
                run.getHypothesis(),
                run.getSetupNotes(),
                run.getMetricsJson(),
                run.getResultSummary(),
                run.getCreatedAt(),
                run.getCompletedAt());
    }
}
