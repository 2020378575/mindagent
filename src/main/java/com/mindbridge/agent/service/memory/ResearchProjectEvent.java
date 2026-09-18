package com.mindbridge.agent.service.memory;

import java.time.Instant;

/**
 * 项目短期事件。写入 Redis 有序集合，供近期上下文召回。
 */
public record ResearchProjectEvent(
        String kind,
        String summary,
        Long relatedId,
        Instant at
) {
    public ResearchProjectEvent {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Project event kind is required");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Project event summary is required");
        }
        if (at == null) {
            at = Instant.now();
        }
    }
}
