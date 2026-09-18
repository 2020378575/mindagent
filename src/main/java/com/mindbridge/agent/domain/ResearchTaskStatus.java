package com.mindbridge.agent.domain;

/**
 * 研究任务执行状态。WAITING_FOR_CONFIRMATION 及之后的终态不再自动推进。
 */
public enum ResearchTaskStatus {
    PENDING,
    RUNNING,
    WAITING_FOR_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
