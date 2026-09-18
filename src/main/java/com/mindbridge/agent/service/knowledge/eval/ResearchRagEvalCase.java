package com.mindbridge.agent.service.knowledge.eval;

import com.mindbridge.agent.domain.IntentType;
import java.util.List;

/**
 * EvidenceLab 版本化评测样本。
 */
public record ResearchRagEvalCase(
        String id,
        Long projectId,
        String question,
        IntentType expectedIntent,
        List<String> expectedSources,
        List<String> requiredClaims,
        List<String> opposingClaims,
        List<String> forbiddenClaims
) {
}
