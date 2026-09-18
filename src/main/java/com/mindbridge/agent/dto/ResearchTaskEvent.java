package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import java.time.Instant;

/**
 * 任务 SSE 事件。只是持久化状态的投影，断线不会取消任务。
 */
public record ResearchTaskEvent(
        String taskPublicId,
        ResearchTaskStatus status,
        ResearchTaskStage stage,
        int progressPercent,
        String message,
        Instant at
) {
}
