package com.mindbridge.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 学生发起聊天请求。
 *
 * @param projectId 当前研究项目；会话与检索都必须保持在该项目内
 * @param sessionId 为空时创建新会话；非空时继续当前项目中的已有会话
 * @param message 学生本轮输入
 */
public record ChatRequest(
        @NotNull Long projectId,
        String sessionId,
        @NotBlank @Size(max = 4000) String message
) {
}
