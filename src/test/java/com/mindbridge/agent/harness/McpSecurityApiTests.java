package com.mindbridge.agent.harness;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-mcp-security;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "spring.ai.mcp.server.enabled=true",
        "spring.ai.mcp.server.sse-endpoint=" + McpSecurityApiTests.SSE_PATH,
        "spring.ai.mcp.server.sse-message-endpoint=" + McpSecurityApiTests.MESSAGE_PATH
})
@AutoConfigureWebTestClient
class McpSecurityApiTests {

    static final String SSE_PATH = "/private-sse";
    static final String MESSAGE_PATH = "/private-messages";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void configuredMcpPathsRequireAdmin() {
        webTestClient.get().uri(SSE_PATH).exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(SSE_PATH)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isForbidden();
        webTestClient.post().uri(MESSAGE_PATH)
                .bodyValue("{}")
                .exchange().expectStatus().isUnauthorized();
    }
}
