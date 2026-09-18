package com.mindbridge.agent.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.ai.PromptTemplates;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 产出决策草案，或对实验结果做复核。
 */
@Component
public class DecisionAgent implements ResearchAgent {

    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public DecisionAgent(AiClient aiClient, ObjectMapper objectMapper) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentName name() {
        return AgentName.DECISION_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.evidenceCritiqued()
                && !context.responseCompleted()
                && context.needsDecision();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        DecisionDraft draft = draft(context);
        context.setDecisionDraft(draft);
        String summary = """
                推荐：%s
                理由：%s
                最小实验：%s
                成功标准：%s
                """.formatted(
                draft.recommendation(),
                draft.rationale(),
                draft.minimumExperiment(),
                draft.successCriteria());
        context.setResponseMessages(List.of(AiMessage.assistant(summary.trim())));
        context.setResponseAgent(AgentName.DECISION_AGENT);
        context.setResponsePlan(context.intent() == IntentType.RESULT_REVIEW
                ? "review experiment against prior decision"
                : "draft research decision");
        context.markResponseCompleted();
        AgentAction action = context.intent() == IntentType.RESULT_REVIEW
                ? AgentAction.REVIEW_RESULT
                : AgentAction.DRAFT_DECISION;
        return AgentDecision.finish(action, "recommendation=" + draft.recommendation());
    }

    private DecisionDraft draft(AgentContext context) {
        try {
            String raw = aiClient.complete(PromptTemplates.decisionDraftPrompt(
                    context.intent(),
                    context.modelInput(),
                    context.retrievedEvidence(),
                    context.critique()));
            return parse(raw, context);
        } catch (Exception exception) {
            return heuristic(context);
        }
    }

    private DecisionDraft parse(String raw, AgentContext context) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(raw));
        List<DecisionOption> options = new ArrayList<>();
        if (root.path("options").isArray()) {
            for (JsonNode option : root.path("options")) {
                options.add(new DecisionOption(
                        option.path("label").asText(""),
                        option.path("summary").asText(""),
                        option.path("score").asDouble(0.0)));
            }
        }
        List<Long> supporting = readLongs(root.path("supportingChunkIds"));
        List<Long> opposing = readLongs(root.path("opposingChunkIds"));
        List<String> gaps = new ArrayList<>();
        if (root.path("evidenceGaps").isArray()) {
            root.path("evidenceGaps").forEach(node -> gaps.add(node.asText()));
        }
        String recommendation = root.path("recommendation").asText("");
        if (recommendation.isBlank()) {
            return heuristic(context);
        }
        return new DecisionDraft(
                root.path("question").asText(context.originalInput()),
                options,
                recommendation,
                root.path("rationale").asText(""),
                supporting,
                opposing,
                gaps,
                root.path("minimumExperiment").asText(""),
                root.path("successCriteria").asText(""),
                root.path("confidence").asDouble(0.6));
    }

    private DecisionDraft heuristic(AgentContext context) {
        List<Long> supporting = context.retrievedEvidence().stream()
                .map(SearchResult::chunkId)
                .filter(id -> id != null)
                .limit(3)
                .toList();
        EvidenceCritique critique = context.critique();
        List<String> gaps = critique == null ? List.of() : critique.evidenceGaps();
        String recommendation = context.intent() == IntentType.RESULT_REVIEW
                ? "证据不足以完全验证，建议补充对照实验"
                : "优先选择证据更充分的方案，并补最小验证实验";
        return new DecisionDraft(
                context.originalInput(),
                List.of(
                        new DecisionOption("Option A", "证据支持相对更强", 0.6),
                        new DecisionOption("Option B", "证据不足或有反证", 0.4)),
                recommendation,
                "基于当前项目证据与批判结果的保守建议。",
                supporting,
                List.of(),
                gaps,
                "在相同数据和硬件约束下跑一次最小对照。",
                "关键指标不低于基线，且峰值资源满足约束。",
                critique == null ? 0.45 : critique.confidence());
    }

    private List<Long> readLongs(JsonNode node) {
        List<Long> values = new ArrayList<>();
        if (!node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item.canConvertToLong()) {
                values.add(item.asLong());
            }
        }
        return values;
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
