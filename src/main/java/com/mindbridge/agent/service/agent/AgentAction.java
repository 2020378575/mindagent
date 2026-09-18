package com.mindbridge.agent.service.agent;

/**
 * 研究决策环中的单步动作。
 */
public enum AgentAction {
    LOAD_RESEARCH_CONTEXT,
    ROUTE_INTENT,
    RETRIEVE_EVIDENCE,
    CRITIQUE_EVIDENCE,
    ANSWER_QUERY,
    DRAFT_DECISION,
    REVIEW_RESULT
}
