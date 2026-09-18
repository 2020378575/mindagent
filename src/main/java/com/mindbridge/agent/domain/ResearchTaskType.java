package com.mindbridge.agent.domain;

/**
 * 研究任务类型。第一版先落地资料入库，其余类型由后续 Agent 环接入。
 */
public enum ResearchTaskType {
    SOURCE_INGESTION,
    EVIDENCE_QUERY,
    DECISION,
    RESULT_REVIEW
}
