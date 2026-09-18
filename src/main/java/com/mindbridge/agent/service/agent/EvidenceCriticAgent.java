package com.mindbridge.agent.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.PromptTemplates;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 对检索证据做支持/反对/缺口批判。
 */
@Component
public class EvidenceCriticAgent implements ResearchAgent {

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public EvidenceCriticAgent(AiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentName name() {
        return AgentName.EVIDENCE_CRITIC_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.evidenceRetrieved()
                && !context.evidenceCritiqued()
                && context.needsCritique();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        EvidenceCritique critique = critique(context.modelInput(), context.retrievedEvidence());
        context.setCritique(critique);
        context.markEvidenceCritiqued();
        return AgentDecision.continueWith(
                AgentAction.CRITIQUE_EVIDENCE,
                "supported=%d opposed=%d gaps=%d".formatted(
                        critique.supportedClaims().size(),
                        critique.opposedClaims().size(),
                        critique.evidenceGaps().size()));
    }

    private EvidenceCritique critique(String question, List<SearchResult> evidence) {
        try {
            String raw = aiClient.complete(PromptTemplates.evidenceCritiquePrompt(question, evidence));
            return parse(raw, evidence);
        } catch (Exception exception) {
            return heuristic(evidence);
        }
    }

    private EvidenceCritique parse(String raw, List<SearchResult> evidence) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(raw));
        List<EvidenceClaim> supported = readClaims(root.path("supportedClaims"));
        List<EvidenceClaim> opposed = readClaims(root.path("opposedClaims"));
        List<String> gaps = new ArrayList<>();
        if (root.path("evidenceGaps").isArray()) {
            root.path("evidenceGaps").forEach(node -> gaps.add(node.asText()));
        }
        double confidence = root.path("confidence").asDouble(0.6);
        if (supported.isEmpty() && opposed.isEmpty() && gaps.isEmpty()) {
            return heuristic(evidence);
        }
        return new EvidenceCritique(supported, opposed, gaps, confidence);
    }

    private List<EvidenceClaim> readClaims(JsonNode node) {
        List<EvidenceClaim> claims = new ArrayList<>();
        if (!node.isArray()) {
            return claims;
        }
        for (JsonNode item : node) {
            claims.add(new EvidenceClaim(
                    item.path("claim").asText(""),
                    item.path("chunkId").isNull() ? null : item.path("chunkId").asLong(),
                    item.path("stance").asText("SUPPORT"),
                    item.path("note").asText("")));
        }
        return claims;
    }

    private EvidenceCritique heuristic(List<SearchResult> evidence) {
        List<EvidenceClaim> supported = evidence.stream()
                .limit(3)
                .map(result -> new EvidenceClaim(
                        result.content().length() > 120 ? result.content().substring(0, 120) : result.content(),
                        result.chunkId(),
                        "SUPPORT",
                        result.source()))
                .toList();
        List<String> gaps = evidence.isEmpty()
                ? List.of("No project evidence retrieved")
                : List.of();
        return new EvidenceCritique(supported, List.of(), gaps, evidence.isEmpty() ? 0.3 : 0.55);
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }
}
