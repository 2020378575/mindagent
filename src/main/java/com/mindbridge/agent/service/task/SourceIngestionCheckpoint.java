package com.mindbridge.agent.service.task;

import com.mindbridge.agent.domain.ResearchTaskStage;

/**
 * 资料入库检查点。记录已解析章节数、切块数和向量索引是否可用。
 */
public record SourceIngestionCheckpoint(
        ResearchTaskStage stage,
        Long sourceId,
        int sectionCount,
        int chunkCount,
        boolean vectorIndexAvailable
) implements TaskCheckpointPayload {
}
