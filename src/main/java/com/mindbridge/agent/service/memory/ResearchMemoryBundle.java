package com.mindbridge.agent.service.memory;

import java.util.List;

/**
 * ResearchContextAgent 使用的三层记忆打包结果。
 */
public record ResearchMemoryBundle(
        ResearchWorkingMemory working,
        List<String> recentProjectEvents,
        List<ResearchMemoryMatch> longTermMemories
) {
}
