package com.mindbridge.agent.service.agent;

/**
 * 研究决策环专家 Agent。
 *
 * <p>每个 Agent 只负责一个清晰职责，由 AgentRuntimeService 按上下文状态选择下一步。</p>
 */
public interface ResearchAgent {

    AgentName name();

    boolean supports(AgentContext context);

    AgentDecision act(AgentContext context);
}
