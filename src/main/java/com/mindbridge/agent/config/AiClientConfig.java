package com.mindbridge.agent.config;

import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.SpringAiChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 大模型客户端装配配置。
 *
 * <p>默认直连 OpenAI 兼容 HTTP API；业务服务只依赖统一的 {@link AiClient} 接口，
 * 不再依赖本地微调模型。</p>
 */
@Configuration
public class AiClientConfig {

    @Bean
    public AiClient aiClient(MindBridgeProperties properties) {
        String provider = properties.getAi().getProvider().toLowerCase();
        if ("openai".equals(provider)) {
            if (properties.getAi().getOpenai().getApiKey().isBlank()) {
                throw new IllegalStateException("AI_PROVIDER=openai requires OPENAI_API_KEY.");
            }
            OpenAiChatModel model = openAiChatModel(properties);
            return new SpringAiChatClient(model, model);
        }
        if ("ollama".equals(provider)) {
            OllamaChatModel model = ollamaChatModel(properties);
            return new SpringAiChatClient(model, model);
        }
        throw new IllegalArgumentException(
                "Unsupported AI_PROVIDER=" + provider + ". Supported providers: openai, ollama.");
    }

    private OllamaChatModel ollamaChatModel(MindBridgeProperties properties) {
        MindBridgeProperties.Ollama ollama = properties.getAi().getOllama();
        OllamaApi api = OllamaApi.builder()
                .baseUrl(ollama.getBaseUrl())
                .build();
        OllamaOptions options = OllamaOptions.builder()
                .model(ollama.getModel())
                .temperature(properties.getAi().getTemperature())
                .numPredict(properties.getAi().getMaxTokens())
                .topP(0.85)
                .repeatPenalty(1.12)
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .build();
    }

    private OpenAiChatModel openAiChatModel(MindBridgeProperties properties) {
        MindBridgeProperties.OpenAi openai = properties.getAi().getOpenai();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(openai.getBaseUrl())
                .apiKey(openai.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openai.getModel())
                .temperature(properties.getAi().getTemperature())
                .maxTokens(properties.getAi().getMaxTokens())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
