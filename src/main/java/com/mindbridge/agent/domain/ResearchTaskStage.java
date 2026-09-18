package com.mindbridge.agent.domain;

/**
 * 研究任务阶段。用于 Checkpoint 定位与进度展示。
 */
public enum ResearchTaskStage {
    SOURCE_STORAGE,
    PARSING,
    INDEXING,
    CONTEXT,
    EVIDENCE,
    CRITIC,
    DECISION,
    REVIEW
}
