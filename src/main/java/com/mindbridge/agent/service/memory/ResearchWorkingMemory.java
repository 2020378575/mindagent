package com.mindbridge.agent.service.memory;

import java.util.List;

/**
 * 当前任务工作记忆投影。权威来源是 ResearchTaskCheckpoint，Redis 只做缓存。
 */
public record ResearchWorkingMemory(
        Long taskId,
        Long projectId,
        String currentStage,
        String question,
        List<Long> retrievedChunkIds,
        String criticSummary,
        String draftResult
) {
}
