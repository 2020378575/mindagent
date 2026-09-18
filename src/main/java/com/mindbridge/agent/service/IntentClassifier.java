package com.mindbridge.agent.service;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.agent.IntentClassification;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.PromptTemplates;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
/**
 * 研究意图分类。优先硬规则，模型仅作兜底。
 */
public class IntentClassifier {

    private static final List<String> DECISION_WORDS = List.of(
            "该选", "还是", "决策", "选择", "对比", "取舍", "优先", "推荐方案", "lora", "qlora");
    private static final List<String> REVIEW_WORDS = List.of(
            "是否验证", "验证了", "实验结果", "run-", "复核", "对照实验", "是否支持之前");
    private static final List<String> EVIDENCE_WORDS = List.of(
            "论文", "配置", "证据", "出处", "引用", "量化", "哪一页", "使用了什么", "根据资料");

    private final AiClient aiClient;

    public IntentClassifier(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    public IntentClassification classify(String input) {
        String normalized = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, REVIEW_WORDS)) {
            return new IntentClassification(IntentType.RESULT_REVIEW, 0.9);
        }
        if (containsAny(normalized, DECISION_WORDS) && (normalized.contains("还是") || normalized.contains("该选")
                || normalized.contains("选择") || normalized.contains("决策"))) {
            return new IntentClassification(IntentType.RESEARCH_DECISION, 0.88);
        }
        if (containsAny(normalized, EVIDENCE_WORDS)) {
            return new IntentClassification(IntentType.EVIDENCE_QUERY, 0.85);
        }
        try {
            String label = aiClient.complete(PromptTemplates.intentPrompt(input)).trim().toUpperCase(Locale.ROOT);
            if (label.contains("RESULT_REVIEW") || label.contains("REVIEW")) {
                return new IntentClassification(IntentType.RESULT_REVIEW, 0.7);
            }
            if (label.contains("RESEARCH_DECISION") || label.contains("DECISION")) {
                return new IntentClassification(IntentType.RESEARCH_DECISION, 0.7);
            }
            if (label.contains("EVIDENCE_QUERY") || label.contains("EVIDENCE")) {
                return new IntentClassification(IntentType.EVIDENCE_QUERY, 0.7);
            }
            if (label.contains("GENERAL_CHAT") || label.equals("CHAT")) {
                return new IntentClassification(IntentType.GENERAL_CHAT, 0.7);
            }
        } catch (Exception ignored) {
            // keyword fallback below
        }
        if (containsAny(normalized, DECISION_WORDS)) {
            return new IntentClassification(IntentType.RESEARCH_DECISION, 0.6);
        }
        return new IntentClassification(IntentType.GENERAL_CHAT, 0.55);
    }

    private boolean containsAny(String input, List<String> words) {
        return words.stream().anyMatch(input::contains);
    }
}
