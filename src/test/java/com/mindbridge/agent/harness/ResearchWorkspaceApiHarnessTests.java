package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.CreateExperimentRequest;
import com.mindbridge.agent.dto.CreateResearchProjectRequest;
import com.mindbridge.agent.dto.CreateResearchTaskRequest;
import com.mindbridge.agent.dto.DecisionResponse;
import com.mindbridge.agent.dto.ExperimentResponse;
import com.mindbridge.agent.dto.ResearchProjectResponse;
import com.mindbridge.agent.dto.ResearchTaskResponse;
import com.mindbridge.agent.dto.ResearchWorkspaceResponse;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-workspace-harness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "mindbridge.knowledge.use-chroma=false",
        "mindbridge.memory.use-chroma=false",
        "mindbridge.knowledge.reranker-enabled=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
@AutoConfigureWebTestClient
class ResearchWorkspaceApiHarnessTests {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AiClient aiClient;

    @MockBean
    private ShortTermMemoryService shortTermMemoryService;

    @MockBean
    private UserProfileMemoryService userProfileMemoryService;

    @BeforeEach
    void setUp() {
        ResearchScriptedAiClient scripted = new ResearchScriptedAiClient();
        when(aiClient.complete(anyList())).thenAnswer(invocation ->
                scripted.complete(invocation.getArgument(0)));
        when(aiClient.stream(anyList())).thenAnswer(invocation ->
                scripted.stream(invocation.getArgument(0)));
        when(shortTermMemoryService.recent(anyString())).thenReturn(List.of());
        when(userProfileMemoryService.profileBrief(any(UserAccount.class), anyString()))
                .thenReturn("无已保存用户画像。");
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(60)).build();
    }

    @Test
    void projectToExperimentJourneyExposesValidatingWorkspace() {
        Long projectId = createProject();
        uploadSource(projectId, "qlora-paper.md");
        ResearchTaskResponse decisionTask = createDecisionTask(projectId, "12GB 显存该选 LoRA 还是 QLoRA？");
        ResearchTaskResponse waiting = awaitTask(projectId, decisionTask.publicId(), ResearchTaskStatus.WAITING_FOR_CONFIRMATION);
        String decisionId = confirmDecision(projectId, waiting.id());
        String experimentId = createExperiment(projectId, Long.valueOf(decisionId));
        submitExperimentResult(projectId, Long.valueOf(experimentId));

        String workspace = loadWorkspace(projectId);
        assertThat(workspace)
                .contains(decisionId)
                .contains(experimentId)
                .contains("VALIDATING");
    }

    private Long createProject() {
        ResearchProjectResponse project = auth()
                .bodyValue(new CreateResearchProjectRequest(
                        "QLoRA workspace",
                        "Choose adapter strategy under 12GB VRAM",
                        "single 12GB GPU"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ResearchProjectResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(project).isNotNull();
        return project.id();
    }

    private void uploadSource(Long projectId, String filename) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource("""
                # QLoRA Notes
                QLoRA uses NF4 quantization and LoRA adapters to reduce peak memory under 12GB.
                """.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).filename(filename);

        webTestClient.post()
                .uri("/api/projects/{projectId}/sources", projectId)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isAccepted();
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private ResearchTaskResponse createDecisionTask(Long projectId, String question) {
        return webTestClient.post()
                .uri("/api/projects/{projectId}/tasks", projectId)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateResearchTaskRequest(
                        UUID.randomUUID().toString(),
                        ResearchTaskType.DECISION,
                        question,
                        null,
                        null,
                        null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ResearchTaskResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private ResearchTaskResponse awaitTask(Long projectId, String publicId, ResearchTaskStatus expected) {
        ResearchTaskResponse latest = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            latest = webTestClient.get()
                    .uri("/api/projects/{projectId}/tasks/{publicId}", projectId, publicId)
                    .headers(headers -> headers.setBasicAuth("student", "student123"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(ResearchTaskResponse.class)
                    .returnResult()
                    .getResponseBody();
            if (latest != null && latest.status() == expected) {
                return latest;
            }
            if (latest != null && latest.status() == ResearchTaskStatus.FAILED) {
                throw new AssertionError("Task failed: " + latest.errorMessage());
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for task");
            }
        }
        throw new AssertionError("Timed out waiting for " + expected + ", last=" + latest);
    }

    private String confirmDecision(Long projectId, Long taskId) {
        DecisionResponse draft = webTestClient.post()
                .uri("/api/projects/{projectId}/decisions/from-task/{taskId}", projectId, taskId)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(DecisionResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(draft).isNotNull();

        DecisionResponse confirmed = webTestClient.post()
                .uri("/api/projects/{projectId}/decisions/{decisionId}/confirm", projectId, draft.id())
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(DecisionResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(confirmed).isNotNull();
        return String.valueOf(confirmed.id());
    }

    private String createExperiment(Long projectId, Long decisionId) {
        ExperimentResponse experiment = webTestClient.post()
                .uri("/api/projects/{projectId}/experiments", projectId)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateExperimentRequest(
                        decisionId,
                        "12GB smoke run",
                        "QLoRA fits",
                        "batch=8"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ExperimentResponse.class)
                .returnResult()
                .getResponseBody();
        assertThat(experiment).isNotNull();
        return String.valueOf(experiment.id());
    }

    private void submitExperimentResult(Long projectId, Long experimentId) {
        webTestClient.post()
                .uri("/api/projects/{projectId}/experiments/{experimentId}/complete", projectId, experimentId)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"resultSummary":"peak memory stayed under 12GB","metricsJson":"{\\"peakGb\\":10.5}"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    private String loadWorkspace(Long projectId) {
        return webTestClient.get()
                .uri("/api/projects/{projectId}/workspace", projectId)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private WebTestClient.RequestBodySpec auth() {
        return webTestClient.post()
                .uri("/api/projects")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .contentType(MediaType.APPLICATION_JSON);
    }
}
