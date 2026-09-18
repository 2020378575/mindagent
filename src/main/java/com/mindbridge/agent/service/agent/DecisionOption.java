package com.mindbridge.agent.service.agent;

/**
 * 决策草案中的可选项。
 */
public record DecisionOption(
        String label,
        String summary,
        double score
) {
}
