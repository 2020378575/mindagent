package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.repository.AgentRunTraceRepository;
import com.mindbridge.agent.repository.PsychologicalReportRepository;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.memory.ShortTermMemoryService;
import com.mindbridge.agent.service.memory.UserProfileMemoryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-api-sse-harness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "mindbridge.knowledge.use-chroma=false",
        "mindbridge.memory.use-chroma=false",
        "mindbridge.knowledge.reranker-enabled=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
@AutoConfigureWebTestClient
class ApiSseHarnessTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PsychologicalReportRepository reportRepository;

    @Autowired
    private AgentRunTraceRepository traceRepository;

    @MockBean
    private AiClient aiClient;

    @MockBean
    private ShortTermMemoryService shortTermMemoryService;

    @MockBean
    private UserProfileMemoryService userProfileMemoryService;

    private ScriptedAiClient scriptedAiClient;

    @BeforeEach
    void setUp() {
        reportRepository.deleteAll();
        traceRepository.deleteAll();
        scriptedAiClient = new ScriptedAiClient();
        when(aiClient.complete(anyList())).thenAnswer(invocation ->
                scriptedAiClient.complete(invocation.getArgument(0)));
        when(aiClient.stream(anyList())).thenAnswer(invocation ->
                scriptedAiClient.stream(invocation.getArgument(0)));
        when(shortTermMemoryService.recent(anyString())).thenReturn(List.of());
        when(userProfileMemoryService.profileBrief(any(UserAccount.class), anyString()))
                .thenReturn("无已保存用户画像。");
    }

    @Test
    void studentChatReturnsSseMetaTokenAndDoneWithoutPsychologyReport() {
        String body = postChat("student", "student123", "帮我解释一下 Java 多线程。");

        assertThat(body)
                .contains("event:meta")
                .contains("event:token")
                .contains("event:done");
        assertThat(reportRepository.findAll()).isEmpty();
        assertThat(traceRepository.findAll()).singleElement()
                .satisfies(trace -> {
                    assertThat(trace.getIntent().name()).isEqualTo("GENERAL_CHAT");
                    assertThat(trace.getStepCount()).isGreaterThanOrEqualTo(3);
                });
    }

    @Test
    void researchChatDoesNotCreatePsychologyReportOrToolChain() {
        String body = postChat("student", "student123", "解释一下 AdamW");

        assertThat(body)
                .contains("event:meta")
                .contains("event:token")
                .contains("event:done");
        assertThat(reportRepository.findAll()).isEmpty();
        assertThat(traceRepository.findAll()).isNotEmpty();
    }

    @Test
    void adminAccountCannotStartStudentChat() {
        webTestClient.post()
                .uri("/api/research/assistant/stream")
                .headers(headers -> headers.setBasicAuth("admin", "admin123"))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"message":"我想用管理员账号发起聊天"}
                        """)
                .exchange()
                .expectStatus().isForbidden();
    }

    private String postChat(String username, String password, String message) {
        EntityExchangeResult<String> result = webTestClient.post()
                .uri("/api/research/assistant/stream")
                .headers(headers -> headers.setBasicAuth(username, password))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"message":"%s"}
                        """.formatted(message))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult();
        return result.getResponseBody() == null ? "" : result.getResponseBody();
    }
}
