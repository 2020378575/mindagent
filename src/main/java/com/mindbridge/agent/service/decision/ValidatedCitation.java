package com.mindbridge.agent.service.decision;

import com.mindbridge.agent.domain.EvidenceStance;

/**
 * 已通过项目归属与任务证据门禁的引用。
 */
public record ValidatedCitation(
        Long chunkId,
        EvidenceStance stance,
        String sourceTitle,
        String excerpt
) {
}
