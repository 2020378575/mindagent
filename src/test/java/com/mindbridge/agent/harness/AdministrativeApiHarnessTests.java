package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.domain.ProjectStatus;
import com.mindbridge.agent.dto.CreateResearchProjectRequest;
import com.mindbridge.agent.dto.KnowledgeIngestRequest;
import com.mindbridge.agent.dto.KnowledgeIngestResponse;
import com.mindbridge.agent.dto.ResearchProjectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-admin-harness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "mindbridge.knowledge.use-chroma=false",
        "mindbridge.memory.use-chroma=false",
        "mindbridge.knowledge.reranker-enabled=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
@AutoConfigureWebTestClient
class AdministrativeApiHarnessTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void separatesPublicStudentAndAdministratorAccess() {
        webTestClient.get()
                .uri("/api/profile")
                .exchange()
                .expectStatus().isUnauthorized();

        student("/api/profile")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("student");

        student("/api/reports/me")
                .exchange()
                .expectStatus().isOk();

        student("/api/profile/memory")
                .exchange()
                .expectStatus().isOk();

        student("/api/admin/reports")
                .exchange()
                .expectStatus().isForbidden();

        admin("/api/admin/reports")
                .exchange()
                .expectStatus().isOk();
        admin("/api/admin/excel-records")
                .exchange()
                .expectStatus().isOk();
        admin("/api/admin/alerts")
                .exchange()
                .expectStatus().isOk();
        admin("/api/admin/run-traces")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void administratorCanIngestKnowledgeButStudentCannot() {
        KnowledgeIngestRequest request = new KnowledgeIngestRequest(
                "admin-harness.md",
                "QLoRA uses NF4 quantization to reduce memory usage on constrained GPUs.");

        webTestClient.post()
                .uri("/api/admin/knowledge")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isForbidden();

        KnowledgeIngestResponse response = webTestClient.post()
                .uri("/api/admin/knowledge")
                .headers(headers -> headers.setBasicAuth("admin", "admin123"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(KnowledgeIngestResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.source()).isEqualTo("admin-harness.md");
        assertThat(response.chunks()).isPositive();
    }

    @Test
    void projectLifecycleIsIsolatedAndMissingResourcesReturnNotFound() {
        ResearchProjectResponse created = webTestClient.post()
                .uri("/api/projects")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateResearchProjectRequest(
                        "Archive harness",
                        "Verify project lifecycle and isolation.",
                        null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ResearchProjectResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo(ProjectStatus.ACTIVE);

        webTestClient.get()
                .uri("/api/projects/{projectId}", created.id())
                .headers(headers -> headers.setBasicAuth("admin", "admin123"))
                .exchange()
                .expectStatus().isNotFound();

        ResearchProjectResponse archived = webTestClient.post()
                .uri("/api/projects/{projectId}/archive", created.id())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ResearchProjectResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(archived).isNotNull();
        assertThat(archived.status()).isEqualTo(ProjectStatus.ARCHIVED);

        admin("/api/admin/conversations/does-not-exist")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.delete()
                .uri("/api/profile/memory/{memoryId}", Long.MAX_VALUE)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isNotFound();
    }

    private WebTestClient.RequestHeadersSpec<?> student(String uri) {
        return webTestClient.get()
                .uri(uri)
                .headers(headers -> headers.setBasicAuth("student", "student123"));
    }

    private WebTestClient.RequestHeadersSpec<?> admin(String uri) {
        return webTestClient.get()
                .uri(uri)
                .headers(headers -> headers.setBasicAuth("admin", "admin123"));
    }
}
