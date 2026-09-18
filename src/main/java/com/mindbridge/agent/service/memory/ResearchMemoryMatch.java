package com.mindbridge.agent.service.memory;

import com.mindbridge.agent.domain.MemoryValidationStatus;

/**
 * 长期记忆语义召回命中。
 */
public record ResearchMemoryMatch(
        Long memoryId,
        Long projectId,
        String summary,
        MemoryValidationStatus validationStatus,
        double score
) {
}
