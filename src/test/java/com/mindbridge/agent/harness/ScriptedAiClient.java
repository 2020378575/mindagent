package com.mindbridge.agent.harness;

import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;

final class ScriptedAiClient implements AiClient {

    private final List<String> completePrompts = new CopyOnWriteArrayList<>();
    private final List<String> streamPrompts = new CopyOnWriteArrayList<>();

    @Override
    public String complete(List<AiMessage> messages) {
        String prompt = joined(messages);
        completePrompts.add(prompt);
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (prompt.contains("用户意图分类器") || prompt.contains("研究工作区意图分类器")) {
            return classify(userPayload(prompt, normalized));
        }
        if (prompt.contains("科研助手消息")) {
            return assessment(normalized);
        }
        if (prompt.contains("MemoryAgent")) {
            return "无相关历史记忆。";
        }
        if (prompt.contains("用户画像记忆抽取器")) {
            return "[]";
        }
        if (prompt.contains("KnowledgeAgent") && prompt.contains("改写成适合检索")) {
            return "LoRA QLoRA VRAM 12GB";
        }
        if (prompt.contains("判断检索结果是否足以")) {
            return "SUFFICIENT";
        }
        if (prompt.contains("RAG reranker")) {
            return "[{\"index\":1,\"score\":0.99},{\"index\":2,\"score\":0.70}]";
        }
        if (prompt.contains("CompanionAgent")) {
            return "直接回答用户的普通学习或生活问题，不引导成无关闲聊。";
        }
        if (prompt.contains("ResearchAssistantAgent")) {
            return "根据项目证据，优先给出可验证的适配器建议。";
        }
        if (prompt.contains("RAG 回答生成器") || prompt.contains("research answer generator")) {
            return ragAnswer(normalized);
        }
        return "根据检索上下文给出简要研究结论。";
    }

    @Override
    public Flux<String> stream(List<AiMessage> messages) {
        String prompt = joined(messages);
        streamPrompts.add(prompt);
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "lora", "qlora", "oom", "vram")) {
            return Flux.just("先核对 GPU 显存与 batch，", "再对照 QLoRA 基线配置。");
        }
        return Flux.just("这是一个稳定的测试回复。");
    }

    List<String> completePrompts() {
        return List.copyOf(completePrompts);
    }

    List<String> streamPrompts() {
        return List.copyOf(streamPrompts);
    }

    private String classify(String prompt) {
        if (containsAny(prompt, "run-019", "是否验证", "复核", "对照实验", "实验结果")) {
            return "RESULT_REVIEW";
        }
        if (containsAny(prompt, "该选", "还是", "决策", "取舍")) {
            return "RESEARCH_DECISION";
        }
        if (containsAny(prompt, "论文", "配置", "证据", "引用", "量化", "readme", "根据资料")) {
            return "EVIDENCE_QUERY";
        }
        return "GENERAL_CHAT";
    }

    private String userPayload(String prompt, String normalizedFallback) {
        int userIdx = prompt.lastIndexOf("user:");
        if (userIdx >= 0) {
            return prompt.substring(userIdx).toLowerCase(Locale.ROOT);
        }
        return normalizedFallback;
    }

    private String assessment(String prompt) {
        if (containsAny(prompt, "oom", "out of memory")) {
            return """
                    {"emotion":"NORMAL","emotionScore":0.0,"risk":"LOW","confidence":0.80,"summary":"experiment failure signal."}
                    """;
        }
        return """
                {"emotion":"NORMAL","emotionScore":0.0,"risk":"LOW","confidence":0.70,"summary":"no alert signal."}
                """;
    }

    private String ragAnswer(String prompt) {
        String question = researchQuestion(prompt);
        if (containsAny(question, "qlora", "lora", "12gb")) {
            return "Under a 12GB VRAM cap, prefer QLoRA with NF4 over full-precision LoRA.";
        }
        if (containsAny(question, "run-019", "oom")) {
            return "run-019 shows LoRA peaked at 13.8GB and hit CUDA out of memory; retry with QLoRA NF4.";
        }
        if (containsAny(question, "readme", "量化", "学习率", "batch")) {
            return "Baseline uses QLoRA NF4, learning_rate 2e-4, batch_size 4, max_seq_len 1024.";
        }
        return "可以先对照基线 README 与实验日志，再决定是否调整适配器方案。";
    }

    private String joined(List<AiMessage> messages) {
        return String.join("\n", messages.stream()
                .map(message -> message.role() + ": " + message.content())
                .toList());
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String researchQuestion(String prompt) {
        if (prompt.contains("Question:")) {
            return sectionAfter(prompt, "Question:");
        }
        return sectionAfter(prompt, "学生问题：");
    }

    private String sectionAfter(String prompt, String marker) {
        int start = prompt.lastIndexOf(marker);
        if (start < 0) {
            return prompt;
        }
        String section = prompt.substring(start + marker.length());
        int nextBlankLine = section.indexOf("\n\n");
        if (nextBlankLine >= 0) {
            section = section.substring(0, nextBlankLine);
        }
        return section.toLowerCase(Locale.ROOT).trim();
    }
}
