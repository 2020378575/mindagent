package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.CreateResearchProjectRequest;
import com.mindbridge.agent.dto.ResearchProjectResponse;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-project-isolation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "mindbridge.knowledge.use-chroma=false",
        "mindbridge.memory.use-chroma=false",
        "mindbridge.knowledge.reranker-enabled=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
@AutoConfigureWebTestClient
class ProjectIsolationApiTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ResearchProjectService researchProjectService;

    @Test
    void hidesProjectOwnedByAnotherUser() {
        UserAccount otherOwner = new UserAccount();
        otherOwner.setUsername("other-" + UUID.randomUUID());
        otherOwner.setDisplayName("Other Researcher");
        otherOwner.setPassword("encoded");
        otherOwner.setRoles(Set.of("ROLE_USER"));
        otherOwner = userAccountRepository.save(otherOwner);

        ResearchProject otherProject = researchProjectService.create(
                otherOwner.getId(),
                new CreateResearchProjectRequest(
                        "Secret project",
                        "Should not be visible to another user.",
                        "12GB VRAM"));

        webTestClient.get()
                .uri("/api/projects/{projectId}", otherProject.getId())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void currentUserCanReadOwnProject() {
        ResearchProjectResponse created = webTestClient.post()
                .uri("/api/projects")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue(new CreateResearchProjectRequest(
                        "Visible project",
                        "Owned by the current student.",
                        null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ResearchProjectResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();

        ResearchProjectResponse loaded = webTestClient.get()
                .uri("/api/projects/{projectId}", created.id())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ResearchProjectResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(loaded).isNotNull();
        assertThat(loaded.id()).isEqualTo(created.id());
        assertThat(loaded.name()).isEqualTo("Visible project");
    }

    @Test
    void mcpEndpointsRequireAdminAuthentication() {
        webTestClient.get().uri("/sse").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/sse")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isForbidden();
        webTestClient.post().uri("/mcp/messages")
                .bodyValue("{}")
                .exchange().expectStatus().isUnauthorized();
    }
}
