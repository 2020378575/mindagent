package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.DecisionReview;
import com.mindbridge.agent.domain.ReviewVerdict;
import java.time.Instant;

public record DecisionReviewResponse(
        Long id,
        Long decisionId,
        Long experimentId,
        Long reviewTaskId,
        ReviewVerdict verdict,
        String summary,
        Instant createdAt
) {
    public static DecisionReviewResponse from(DecisionReview review) {
        return new DecisionReviewResponse(
                review.getId(),
                review.getDecision() == null ? null : review.getDecision().getId(),
                review.getExperimentId(),
                review.getReviewTaskId(),
                review.getVerdict(),
                review.getSummary(),
                review.getCreatedAt());
    }
}
