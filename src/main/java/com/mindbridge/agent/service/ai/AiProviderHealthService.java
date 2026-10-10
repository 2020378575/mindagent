package com.mindbridge.agent.service.ai;

import com.mindbridge.agent.config.MindBridgeProperties;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class AiProviderHealthService {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final String OLLAMA_SHOW_PATH = "/api/show";
    private static final String OPENAI_MODELS_PATH = "/v1/models";
    private static final String VERSIONED_API_SUFFIX = "/v1";

    private final MindBridgeProperties properties;
    private final WebClient.Builder webClientBuilder;

    public AiProviderHealthService(MindBridgeProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
    }

    public Mono<Boolean> isAvailable() {
        String provider = properties.getAi().getProvider().toLowerCase(Locale.ROOT);
        Mono<Boolean> probe = switch (provider) {
            case AiProviders.OLLAMA -> probeOllama();
            case AiProviders.OPENAI -> probeOpenAi();
            default -> Mono.just(false);
        };
        return probe.timeout(PROBE_TIMEOUT).onErrorReturn(false);
    }

    private Mono<Boolean> probeOllama() {
        MindBridgeProperties.Ollama ollama = properties.getAi().getOllama();
        String url = trimTrailingSlash(ollama.getBaseUrl()) + OLLAMA_SHOW_PATH;
        WebClient.RequestHeadersSpec<?> request = webClientBuilder.clone()
                .build()
                .post()
                .uri(url)
                .bodyValue(new OllamaShowRequest(ollama.getModel()));
        return successful(request);
    }

    private Mono<Boolean> probeOpenAi() {
        MindBridgeProperties.OpenAi openAi = properties.getAi().getOpenai();
        if (openAi.getApiKey() == null || openAi.getApiKey().isBlank()) {
            return Mono.just(false);
        }
        String baseUrl = trimTrailingSlash(openAi.getBaseUrl());
        String url = baseUrl.endsWith(VERSIONED_API_SUFFIX)
                ? baseUrl + "/models"
                : baseUrl + OPENAI_MODELS_PATH;
        WebClient.RequestHeadersSpec<?> request = webClientBuilder.clone()
                .build()
                .get()
                .uri(url)
                .headers(headers -> headers.setBearerAuth(openAi.getApiKey()));
        return successful(request);
    }

    private Mono<Boolean> successful(WebClient.RequestHeadersSpec<?> request) {
        return request.exchangeToMono(response -> Mono.just(response.statusCode().is2xxSuccessful()));
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private record OllamaShowRequest(String name) {
    }
}
