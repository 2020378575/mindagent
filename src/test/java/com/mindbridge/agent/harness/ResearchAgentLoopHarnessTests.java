package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.agent.AgentAction;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentContextCheckpoint;
import com.mindbridge.agent.service.agent.AgentName;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentRuntimeService;
import com.mindbridge.agent.service.agent.AgentStep;
import com.mindbridge.agent.service.agent.DecisionAgent;
import com.mindbridge.agent.service.agent.EvidenceAgent;
import com.mindbridge.agent.service.agent.EvidenceCriticAgent;
import com.mindbridge.agent.service.agent.ResearchAssistantAgent;
import com.mindbridge.agent.service.agent.ResearchContextAgent;
import com.mindbridge.agent.service.agent.SupervisorAgent;
import com.mindbridge.agent.service.knowledge.ProjectKnowledgeService;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.memory.ResearchLongTermMemoryService;
import com.mindbridge.agent.service.memory.ResearchMemoryBundle;
import com.mindbridge.agent.service.memory.ResearchWorkingMemory;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResearchAgentLoopHarnessTests {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 11L;
    private static final Long TASK_ID = 99L;

    private ResearchScriptedAiClient aiClient;
    private ProjectKnowledgeService projectKnowledgeService;
    private ResearchTaskService researchTaskService;
    private AgentRuntimeService runtimeService;

    @BeforeEach
    void setUp() {
        aiClient = new ResearchScriptedAiClient();
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getKnowledge().setTopK(2);

        ResearchLongTermMemoryService longTermMemoryService = mock(ResearchLongTermMemoryService.class);
        projectKnowledgeService = mock(ProjectKnowledgeService.class);
        researchTaskService = mock(ResearchTaskService.class);

        when(longTermMemoryService.load(any(), any(), any(), anyString()))
                .thenReturn(new ResearchMemoryBundle(
                        new ResearchWorkingMemory(TASK_ID, PROJECT_ID, "CONTEXT", "q", List.of(), null, null),
                        List.of("SOURCE_READY: notes.md"),
                        List.of()));
        when(projectKnowledgeService.retrieve(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(SearchResult.of(
                        1L,
                        "qlora-paper.md",
                        "QLoRA uses NF4 quantization and LoRA adapters to reduce peak memory.",
                        0.93)));

        runtimeService = new AgentRuntimeService(
                new ResearchContextAgent(longTermMemoryService),
                new SupervisorAgent(new IntentClassifier(aiClient)),
                new EvidenceAgent(projectKnowledgeService, properties, aiClient),
                new EvidenceCriticAgent(aiClient, new ObjectMapper()),
                new ResearchAssistantAgent(aiClient),
                new DecisionAgent(aiClient, new ObjectMapper()),
                researchTaskService,
                new ObjectMapper());
    }

    @Test
    void generalChatRouteUsesAssistantOnly() {
        assertThat(agentNames(run("解释一下 AdamW", IntentType.GENERAL_CHAT)))
                .containsExactly(
                        AgentName.RESEARCH_CONTEXT_AGENT,
                        AgentName.SUPERVISOR_AGENT,
                        AgentName.RESEARCH_ASSISTANT_AGENT);
    }

    @Test
    void evidenceQueryRouteRetrievesThenAnswers() {
        assertThat(agentNames(run("论文中 QLoRA 使用了什么量化配置？", IntentType.EVIDENCE_QUERY)))
                .containsExactly(
                        AgentName.RESEARCH_CONTEXT_AGENT,
                        AgentName.SUPERVISOR_AGENT,
                        AgentName.EVIDENCE_AGENT,
                        AgentName.RESEARCH_ASSISTANT_AGENT);
    }

    @Test
    void researchDecisionRouteCritiquesThenDrafts() {
        assertThat(agentNames(run("12GB 显存该选 LoRA 还是 QLoRA？", IntentType.RESEARCH_DECISION)))
                .containsExactly(
                        AgentName.RESEARCH_CONTEXT_AGENT,
                        AgentName.SUPERVISOR_AGENT,
                        AgentName.EVIDENCE_AGENT,
                        AgentName.EVIDENCE_CRITIC_AGENT,
                        AgentName.DECISION_AGENT);
    }

    @Test
    void resultReviewRouteReusesDecisionPath() {
        assertThat(agentNames(run("run-019 是否验证了之前的决策？", IntentType.RESULT_REVIEW)))
                .containsExactly(
                        AgentName.RESEARCH_CONTEXT_AGENT,
                        AgentName.SUPERVISOR_AGENT,
                        AgentName.EVIDENCE_AGENT,
                        AgentName.EVIDENCE_CRITIC_AGENT,
                        AgentName.DECISION_AGENT);
    }

    @Test
    void resumeSkipsCompletedStagesAndContinuesFromCheckpoint() {
        AgentContext context = new AgentContext(TASK_ID, USER_ID, PROJECT_ID, "12GB 显存该选 LoRA 还是 QLoRA？", null);
        context.setExpectedIntent(IntentType.RESEARCH_DECISION);
        context.restoreFromCheckpoint(new AgentContextCheckpoint(
                ResearchTaskStage.EVIDENCE,
                IntentType.RESEARCH_DECISION,
                List.of(1L),
                null,
                null,
                true,
                true,
                true,
                false,
                false,
                "QLoRA VRAM",
                null));
        context.setResumeFromStep(4);
        context.setRetrievedEvidence(List.of(SearchResult.of(
                1L,
                "qlora-paper.md",
                "QLoRA uses NF4 quantization and LoRA adapters to reduce peak memory.",
                0.93)));

        AgentRunResult result = runtimeService.run(context);

        assertThat(agentNames(result)).containsExactly(
                AgentName.EVIDENCE_CRITIC_AGENT,
                AgentName.DECISION_AGENT);
        assertThat(result.steps())
                .extracting(AgentStep::action)
                .containsExactly(AgentAction.CRITIQUE_EVIDENCE, AgentAction.DRAFT_DECISION);
        verify(projectKnowledgeService, never()).retrieve(anyLong(), anyString(), anyInt());
        verify(researchTaskService, org.mockito.Mockito.times(2)).saveCheckpoint(
                anyLong(), anyInt(), anyString(), any(ResearchTaskStage.class), any());
    }

    private AgentRunResult run(String input, IntentType expectedIntent) {
        AgentContext context = new AgentContext(TASK_ID, USER_ID, PROJECT_ID, input, input);
        context.setExpectedIntent(expectedIntent);
        return runtimeService.run(context);
    }

    private List<AgentName> agentNames(AgentRunResult result) {
        return result.steps().stream().map(AgentStep::agent).toList();
    }
}
