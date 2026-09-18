package com.mindbridge.agent.dto;

/**
 * 上传资料后立即返回来源与入库任务，HTTP 202。
 */
public record CreateSourceResponse(
        ResearchSourceResponse source,
        ResearchTaskResponse task
) {
}
