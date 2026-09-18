package com.mindbridge.agent.domain;

/**
 * 长期记忆校验状态。只有 CONFIRMED / EXPERIMENT_VERIFIED 可进入主动召回。
 */
public enum MemoryValidationStatus {
    CONFIRMED,
    EXPERIMENT_VERIFIED,
    REFUTED
}
