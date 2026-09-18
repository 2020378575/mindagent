package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.memory.ResearchMemoryBundle;
import java.util.List;

/**
 * Agent loop 完成后的结构化结果。
 */
public record AgentRunResult(
        IntentType intent,
        List<SearchResult> retrievedEvidence,
        EvidenceCritique critique,
        DecisionDraft decisionDraft,
        ResearchMemoryBundle memoryBundle,
        List<AiMessage> responseMessages,
        String memoryBrief,
        String knowledgeQuery,
        String responsePlan,
        AgentName responseAgent,
        List<AgentStep> steps
) {
    public static AgentRunResult from(AgentContext context) {
        return new AgentRunResult(
                context.intent(),
                context.retrievedEvidence(),
                context.critique(),
                context.decisionDraft(),
                context.memoryBundle(),
                context.responseMessages(),
                context.memoryBrief(),
                context.knowledgeQuery(),
                context.responsePlan(),
                context.responseAgent(),
                context.steps()
        );
    }

    /** 兼容旧聊天落库字段：研究环不再做心理风险评估。 */
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    public List<SearchResult> retrievedKnowledge() {
        return retrievedEvidence;
    }

    public List<AiMessage> modelHistory() {
        return List.of();
    }

    public boolean requiresReport() {
        return false;
    }
}
