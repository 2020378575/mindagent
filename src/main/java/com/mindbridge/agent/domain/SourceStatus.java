package com.mindbridge.agent.domain;

/**
 * 研究资料解析与索引状态。失败必须可观察，不能只停在内存里。
 */
public enum SourceStatus {
    PENDING,
    PARSING,
    INDEXING,
    READY,
    FAILED
}
