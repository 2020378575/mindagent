package com.mindbridge.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.ai.AiProviderHealthService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AgentStatusControllerTests {

    @Test
    void reportsConfiguredModelAsUnavailableWhenProviderProbeFails() {
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getAi().setProvider("ollama");
        properties.getAi().getOllama().setModel("qwen2.5:7b");
        AiProviderHealthService healthService = mock(AiProviderHealthService.class);
        when(healthService.isAvailable()).thenReturn(Mono.just(false));

        AgentStatusController controller = new AgentStatusController(properties, healthService);

        StepVerifier.create(controller.status())
                .assertNext(status -> {
                    assertThat(status.model()).isEqualTo("qwen2.5:7b");
                    assertThat(status.modelAvailable()).isFalse();
                    assertThat(status.note()).contains("不可用");
                })
                .verifyComplete();
    }
}
