package com.mindbridge.agent.service.agent;

import java.util.List;

/**
 * EvidenceCriticAgent 产出的结构化批判结果。
 */
public record EvidenceCritique(
        List<EvidenceClaim> supportedClaims,
        List<EvidenceClaim> opposedClaims,
        List<String> evidenceGaps,
        double confidence
) {
    public EvidenceCritique {
        supportedClaims = supportedClaims == null ? List.of() : List.copyOf(supportedClaims);
        opposedClaims = opposedClaims == null ? List.of() : List.copyOf(opposedClaims);
        evidenceGaps = evidenceGaps == null ? List.of() : List.copyOf(evidenceGaps);
    }
}
