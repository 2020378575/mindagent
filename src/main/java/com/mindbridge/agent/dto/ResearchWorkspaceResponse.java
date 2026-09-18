package com.mindbridge.agent.dto;

import java.util.List;

/**
 * 决策优先工作区聚合视图。
 */
public record ResearchWorkspaceResponse(
        ResearchProjectResponse project,
        WorkspaceActiveDecision activeDecision,
        List<DecisionResponse> decisions,
        List<ExperimentResponse> experiments,
        List<ResearchSourceResponse> sources,
        List<ResearchTaskResponse> recentTasks
) {
}
