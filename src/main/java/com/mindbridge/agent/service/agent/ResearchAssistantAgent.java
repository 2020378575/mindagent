package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 普通研究问答与证据查询回答。
 */
@Component
public class ResearchAssistantAgent implements ResearchAgent {

    private final AiClient aiClient;

    public ResearchAssistantAgent(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public AgentName name() {
        return AgentName.RESEARCH_ASSISTANT_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        if (!context.intentRouted() || context.responseCompleted() || context.needsDecision()) {
            return false;
        }
        if (context.intent() == IntentType.GENERAL_CHAT) {
            return true;
        }
        return context.intent() == IntentType.EVIDENCE_QUERY && context.evidenceRetrieved();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        List<SearchResult> evidence = context.retrievedEvidence();
        String answer;
        try {
            answer = aiClient.complete(PromptTemplates.researchAnswerPrompt(
                    context.intent(),
                    context.modelInput(),
                    context.memoryBrief(),
                    evidence)).trim();
        } catch (Exception exception) {
            answer = evidence.isEmpty()
                    ? "当前没有足够的项目证据，只能给出通用说明。"
                    : "基于已检索证据：\n" + evidence.get(0).content();
        }
        if (answer.isBlank()) {
            answer = "暂无可用回答。";
        }
        context.setResponseMessages(List.of(AiMessage.assistant(answer)));
        context.setResponseAgent(AgentName.RESEARCH_ASSISTANT_AGENT);
        context.setResponsePlan("answer research query");
        context.markResponseCompleted();
        return AgentDecision.finish(AgentAction.ANSWER_QUERY, "answered");
    }
}
