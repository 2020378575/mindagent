package com.mindbridge.agent.service.task;

import com.mindbridge.agent.domain.ResearchTaskStage;

/**
 * 检查点载荷。每种阶段对应明确 Java record，禁止把已知结构反序列化成 Map。
 */
public interface TaskCheckpointPayload {

    ResearchTaskStage stage();
}
