package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.DecisionRecord;
import com.mindbridge.agent.domain.DecisionStatus;
import java.time.Instant;

public record DecisionResponse(
        Long id,
        Long projectId,
        Long taskId,
        Long previousDecisionId,
        Long experimentId,
        int version,
        DecisionStatus status,
        String question,
        String recommendation,
        String rationale,
        String minimumExperiment,
        String successCriteria,
        double confidence,
        Instant createdAt,
        Instant confirmedAt
) {
    public static DecisionResponse from(DecisionRecord decision) {
        return new DecisionResponse(
                decision.getId(),
                decision.projectId(),
                decision.getTaskId(),
                decision.getPreviousDecisionId(),
                decision.getExperimentId(),
                decision.getVersion(),
                decision.getStatus(),
                decision.getQuestion(),
                decision.getRecommendation(),
                decision.getRationale(),
                decision.getMinimumExperiment(),
                decision.getSuccessCriteria(),
                decision.getConfidence(),
                decision.getCreatedAt(),
                decision.getConfirmedAt());
    }
}
