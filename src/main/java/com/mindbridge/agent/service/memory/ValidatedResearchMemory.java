package com.mindbridge.agent.service.memory;

import com.mindbridge.agent.domain.MemoryValidationStatus;
import com.mindbridge.agent.domain.ResearchMemorySourceType;
import java.util.List;

/**
 * 唯一允许晋升长期记忆的命令对象。REFUTED 不能作为活跃记忆写入。
 */
public record ValidatedResearchMemory(
        Long projectId,
        ResearchMemorySourceType sourceType,
        Long sourceRecordId,
        MemoryValidationStatus validationStatus,
        String summary,
        List<Long> evidenceChunkIds
) {
    public ValidatedResearchMemory {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId is required");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType is required");
        }
        if (sourceRecordId == null) {
            throw new IllegalArgumentException("sourceRecordId is required");
        }
        if (validationStatus == null) {
            throw new IllegalArgumentException("validationStatus is required");
        }
        if (validationStatus == MemoryValidationStatus.REFUTED) {
            throw new IllegalArgumentException("Refuted evidence cannot be promoted as active memory");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary is required");
        }
        evidenceChunkIds = evidenceChunkIds == null ? List.of() : List.copyOf(evidenceChunkIds);
    }
}
