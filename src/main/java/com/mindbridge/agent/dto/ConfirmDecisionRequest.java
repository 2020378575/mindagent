package com.mindbridge.agent.dto;

/**
 * 确认决策请求。当前无需额外字段，保留扩展位。
 */
public record ConfirmDecisionRequest(
        String note
) {
}
