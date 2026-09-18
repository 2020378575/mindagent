package com.mindbridge.agent.harness;

import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;

/**
 * 研究决策环脚本化模型客户端。
 */
final class ResearchScriptedAiClient implements AiClient {

    private final List<String> completePrompts = new CopyOnWriteArrayList<>();

    @Override
    public String complete(List<AiMessage> messages) {
        String prompt = joined(messages);
        completePrompts.add(prompt);
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (prompt.contains("研究工作区意图分类器") || prompt.contains("意图分类器")) {
            return classify(normalized);
        }
        if (prompt.contains("EvidenceAgent") && prompt.contains("查询改写")) {
            return "QLoRA quantization LoRA VRAM";
        }
        if (prompt.contains("EvidenceCriticAgent")) {
            return """
                    {"supportedClaims":[{"claim":"QLoRA lowers peak memory","chunkId":1,"stance":"SUPPORT","note":"paper"}],
                     "opposedClaims":[],
                     "evidenceGaps":["missing multi-GPU ablation"],
                     "confidence":0.78}
                    """;
        }
        if (prompt.contains("DecisionAgent")) {
            return """
                    {"question":"LoRA or QLoRA",
                     "options":[{"label":"QLoRA","summary":"lower peak memory","score":0.8},
                                {"label":"LoRA","summary":"higher memory","score":0.4}],
                     "recommendation":"QLoRA",
                     "rationale":"Evidence favors lower peak VRAM under 12GB",
                     "supportingChunkIds":[1],
                     "opposingChunkIds":[],
                     "evidenceGaps":["missing multi-GPU ablation"],
                     "minimumExperiment":"train one epoch on 12GB GPU",
                     "successCriteria":"peak memory under 12GB",
                     "confidence":0.72}
                    """;
        }
        if (prompt.contains("ResearchAssistantAgent")) {
            if (normalized.contains("adamw")) {
                return "AdamW 是带解耦权重衰减的优化器，常用于 Transformer 微调。";
            }
            return "根据项目证据，QLoRA 使用 NF4 量化与 LoRA adapter。";
        }
        return "ok";
    }

    @Override
    public Flux<String> stream(List<AiMessage> messages) {
        return Flux.just(complete(messages));
    }

    List<String> completePrompts() {
        return List.copyOf(completePrompts);
    }

    private String classify(String prompt) {
        if (prompt.contains("run-019") || prompt.contains("是否验证")) {
            return "RESULT_REVIEW";
        }
        if (prompt.contains("该选") || prompt.contains("还是")) {
            return "RESEARCH_DECISION";
        }
        if (prompt.contains("量化配置") || prompt.contains("论文")) {
            return "EVIDENCE_QUERY";
        }
        return "GENERAL_CHAT";
    }

    private String joined(List<AiMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (AiMessage message : messages) {
            builder.append(message.role()).append(':').append(message.content()).append('\n');
        }
        return builder.toString();
    }
}
