package com.mindbridge.agent.service.agent;

import java.util.List;

/**
 * DecisionAgent 产出的结构化决策草案。
 */
public record DecisionDraft(
        String question,
        List<DecisionOption> options,
        String recommendation,
        String rationale,
        List<Long> supportingChunkIds,
        List<Long> opposingChunkIds,
        List<String> evidenceGaps,
        String minimumExperiment,
        String successCriteria,
        double confidence
) {
    public DecisionDraft {
        options = options == null ? List.of() : List.copyOf(options);
        supportingChunkIds = supportingChunkIds == null ? List.of() : List.copyOf(supportingChunkIds);
        opposingChunkIds = opposingChunkIds == null ? List.of() : List.copyOf(opposingChunkIds);
        evidenceGaps = evidenceGaps == null ? List.of() : List.copyOf(evidenceGaps);
    }
}
