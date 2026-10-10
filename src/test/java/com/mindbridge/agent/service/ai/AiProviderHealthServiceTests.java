package com.mindbridge.agent.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class AiProviderHealthServiceTests {

    private static final String MODEL_NAME = "test-model:latest";
    private static final String OLLAMA_SHOW_PATH = "/api/show";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsOllamaModelAvailableOnlyWhenShowEndpointAcceptsConfiguredModel() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(OLLAMA_SHOW_PATH, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        AiProviderHealthService service = serviceForRunningServer();

        StepVerifier.create(service.isAvailable())
                .expectNext(true)
                .verifyComplete();
        assertThat(requestBody.get()).contains(MODEL_NAME);
    }

    @Test
    void reportsOllamaModelUnavailableWhenConfiguredModelIsMissing() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(OLLAMA_SHOW_PATH, exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        StepVerifier.create(serviceForRunningServer().isAvailable())
                .expectNext(false)
                .verifyComplete();
    }

    private AiProviderHealthService serviceForRunningServer() {
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getAi().setProvider(AiProviders.OLLAMA);
        properties.getAi().getOllama().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getAi().getOllama().setModel(MODEL_NAME);
        return new AiProviderHealthService(properties, WebClient.builder());
    }
}
