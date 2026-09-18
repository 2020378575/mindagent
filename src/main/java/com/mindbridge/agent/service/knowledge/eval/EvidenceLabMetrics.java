package com.mindbridge.agent.service.knowledge.eval;

/**
 * EvidenceLab 端到端验收指标。
 */
public record EvidenceLabMetrics(
        String datasetVersion,
        String gitCommit,
        double intentAccuracy,
        double recallAtFive,
        double claimSourceSupportRate,
        double structuredOutputSuccessRate,
        long crossProjectLeakageCount,
        int taskRecoveryScenariosPassed,
        int totalCases,
        long passedCases
) {
}
