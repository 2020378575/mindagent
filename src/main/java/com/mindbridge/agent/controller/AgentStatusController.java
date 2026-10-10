package com.mindbridge.agent.controller;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.ai.AiProviderHealthService;
import com.mindbridge.agent.service.ai.AiProviders;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/agent")
/**
 * 智能体运行状态接口。
 *
 * <p>前端用它展示当前 provider、调用的 API 模型名称、RAG 参数和模型连接模式。</p>
 */
public class AgentStatusController {

    private final MindBridgeProperties properties;
    private final AiProviderHealthService healthService;

    public AgentStatusController(MindBridgeProperties properties, AiProviderHealthService healthService) {
        this.properties = properties;
        this.healthService = healthService;
    }

    @GetMapping("/status")
    public Mono<AgentStatusResponse> status() {
        String provider = properties.getAi().getProvider().toLowerCase(Locale.ROOT);
        boolean realModelEnabled = AiProviders.OPENAI.equals(provider) || AiProviders.OLLAMA.equals(provider);
        return healthService.isAvailable().map(modelAvailable -> new AgentStatusResponse(
                    provider,
                    modelName(provider),
                    realModelEnabled,
                    modelAvailable,
                    properties.getKnowledge().isUseChroma(),
                    properties.getKnowledge().getTopK(),
                    properties.getKnowledge().isRerankerEnabled(),
                    properties.getKnowledge().getRerankerCandidateLimit(),
                    properties.getMemory().isUseChroma(),
                    properties.getMemory().getTopK(),
                    "EvidenceLab",
                    modelAvailable ? "模型服务可用。" : "模型服务当前不可用。"
                ));
    }

    private String modelName(String provider) {
        if (AiProviders.OLLAMA.equals(provider)) {
            return properties.getAi().getOllama().getModel();
        }
        if (AiProviders.OPENAI.equals(provider)) {
            return properties.getAi().getOpenai().getModel();
        }
        return "unknown";
    }

    /**
     * 前端状态栏需要的最小状态信息。
     */
    public record AgentStatusResponse(
            String provider,
            String model,
            boolean realModelEnabled,
            boolean modelAvailable,
            boolean chromaEnabled,
            int ragTopK,
            boolean rerankerEnabled,
            int rerankerCandidateLimit,
            boolean memoryChromaEnabled,
            int memoryTopK,
            String product,
            String note
    ) {
    }
}
