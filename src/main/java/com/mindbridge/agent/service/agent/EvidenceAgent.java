package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.PromptTemplates;
import com.mindbridge.agent.service.knowledge.ProjectKnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 按项目检索证据片段。
 */
@Component
public class EvidenceAgent implements ResearchAgent {

    private final ProjectKnowledgeService projectKnowledgeService;
    private final MindBridgeProperties properties;
    private final AiClient aiClient;

    public EvidenceAgent(
            ProjectKnowledgeService projectKnowledgeService,
            MindBridgeProperties properties,
            AiClient aiClient
    ) {
        this.projectKnowledgeService = projectKnowledgeService;
        this.properties = properties;
        this.aiClient = aiClient;
    }

    @Override
    public AgentName name() {
        return AgentName.EVIDENCE_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.intentRouted()
                && !context.evidenceRetrieved()
                && context.needsEvidence();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        String query = rewriteQuery(context.modelInput());
        context.setKnowledgeQuery(query);
        int topK = Math.max(1, properties.getKnowledge().getTopK());
        List<SearchResult> results;
        try {
            results = projectKnowledgeService.retrieve(context.projectId(), query, topK);
        } catch (Exception exception) {
            results = List.of();
        }
        context.setRetrievedEvidence(results);
        context.markEvidenceRetrieved();
        return AgentDecision.continueWith(
                AgentAction.RETRIEVE_EVIDENCE,
                "retrieved=%d query=%s".formatted(results.size(), query));
    }

    private String rewriteQuery(String input) {
        try {
            String rewritten = aiClient.complete(PromptTemplates.evidenceQueryRewritePrompt(input)).trim();
            return rewritten.isBlank() ? input : rewritten;
        } catch (Exception ignored) {
            return input;
        }
    }
}
