package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.DecisionStatus;
import com.mindbridge.agent.domain.ExperimentStatus;
import java.util.List;

/**
 * 项目概览中的当前活跃决策卡片。
 */
public record WorkspaceActiveDecision(
        Long id,
        int version,
        DecisionStatus status,
        String question,
        String recommendation,
        double confidence,
        int supportingEvidenceCount,
        int opposingEvidenceCount,
        List<String> evidenceGaps,
        String minimumExperiment,
        String successCriteria,
        Long experimentId,
        ExperimentStatus experimentStatus
) {
}
