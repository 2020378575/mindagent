package com.mindbridge.agent.service.ai;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.agent.EvidenceCritique;
import com.mindbridge.agent.service.knowledge.SearchResult;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 研究决策环提示词模板。
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    public static List<AiMessage> intentPrompt(String userInput) {
        return List.of(
                AiMessage.system("""
                        你是研究工作区意图分类器，只输出下列标签之一：
                        GENERAL_CHAT：概念解释、闲聊、通用问答，不需要项目证据。
                        EVIDENCE_QUERY：询问论文/资料中的具体配置、数字、出处。
                        RESEARCH_DECISION：在约束下做方法/参数取舍。
                        RESULT_REVIEW：用实验结果复核先前决策。
                        只输出标签，不要解释。
                        """),
                AiMessage.user(userInput == null ? "" : userInput)
        );
    }

    public static List<AiMessage> evidenceQueryRewritePrompt(String userInput) {
        return List.of(
                AiMessage.system("""
                        你是 EvidenceAgent 的查询改写器。
                        把用户问题改写成适合项目资料检索的短查询，保留关键术语与约束，不要回答问题。
                        只输出改写后的查询。
                        """),
                AiMessage.user(userInput == null ? "" : userInput)
        );
    }

    public static List<AiMessage> evidenceCritiquePrompt(String question, List<SearchResult> evidence) {
        return List.of(
                AiMessage.system("""
                        你是 EvidenceCriticAgent。只返回严格 JSON：
                        {"supportedClaims":[{"claim":"...","chunkId":1,"stance":"SUPPORT","note":"..."}],
                         "opposedClaims":[{"claim":"...","chunkId":2,"stance":"OPPOSE","note":"..."}],
                         "evidenceGaps":["..."],"confidence":0.0}
                        不要 Markdown。
                        """),
                AiMessage.user("""
                        问题：
                        %s

                        证据：
                        %s
                        """.formatted(question, formatEvidence(evidence)))
        );
    }

    public static List<AiMessage> researchAnswerPrompt(
            IntentType intent,
            String question,
            String memoryBrief,
            List<SearchResult> evidence
    ) {
        return List.of(
                AiMessage.system("""
                        你是 ResearchAssistantAgent，面向科研助手场景。
                        基于项目记忆与证据回答；证据不足时明确说明，不要编造出处。
                        当前意图：%s
                        """.formatted(intent == null ? "GENERAL_CHAT" : intent.name())),
                AiMessage.user("""
                        记忆摘要：
                        %s

                        证据：
                        %s

                        问题：
                        %s
                        """.formatted(memoryBrief, formatEvidence(evidence), question))
        );
    }

    public static List<AiMessage> decisionDraftPrompt(
            IntentType intent,
            String question,
            List<SearchResult> evidence,
            EvidenceCritique critique
    ) {
        return List.of(
                AiMessage.system("""
                        你是 DecisionAgent。只返回严格 JSON：
                        {"question":"...","options":[{"label":"...","summary":"...","score":0.0}],
                         "recommendation":"...","rationale":"...",
                         "supportingChunkIds":[1],"opposingChunkIds":[2],"evidenceGaps":["..."],
                         "minimumExperiment":"...","successCriteria":"...","confidence":0.0}
                        意图：%s
                        """.formatted(intent == null ? "RESEARCH_DECISION" : intent.name())),
                AiMessage.user("""
                        问题：
                        %s

                        证据：
                        %s

                        批判：
                        %s
                        """.formatted(question, formatEvidence(evidence), formatCritique(critique)))
        );
    }

    public static AiMessage answerSystemPrompt(IntentType intent, String context, String displayName) {
        return AiMessage.system("""
                你是 MindBridge EvidenceLab 研究助手。
                学生显示名：%s
                意图：%s
                优先依据下方证据回答；证据不足时明确说明。
                证据：
                %s
                """.formatted(
                displayName,
                intent == null ? IntentType.GENERAL_CHAT.name() : intent.name(),
                context == null || context.isBlank() ? "无" : context));
    }

    private static String formatEvidence(List<SearchResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "无";
        }
        return evidence.stream()
                .map(result -> "- chunk=%s [%s] %s".formatted(
                        result.chunkId(), result.source(), result.content()))
                .collect(Collectors.joining("\n"));
    }

    private static String formatCritique(EvidenceCritique critique) {
        if (critique == null) {
            return "无";
        }
        return "supported=%d opposed=%d gaps=%s confidence=%.2f".formatted(
                critique.supportedClaims().size(),
                critique.opposedClaims().size(),
                critique.evidenceGaps(),
                critique.confidence());
    }
}
