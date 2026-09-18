package com.mindbridge.agent.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 研究决策环运行时：有限步调度，并为每个完成的 Agent 写检查点。
 */
@Service
public class AgentRuntimeService {

    private static final int MAX_STEPS = 8;

    private final List<ResearchAgent> agents;
    private final ResearchTaskService researchTaskService;
    private final ObjectMapper objectMapper;

    public AgentRuntimeService(
            ResearchContextAgent researchContextAgent,
            SupervisorAgent supervisorAgent,
            EvidenceAgent evidenceAgent,
            EvidenceCriticAgent evidenceCriticAgent,
            ResearchAssistantAgent researchAssistantAgent,
            DecisionAgent decisionAgent,
            ResearchTaskService researchTaskService,
            ObjectMapper objectMapper
    ) {
        this.agents = List.of(
                researchContextAgent,
                supervisorAgent,
                evidenceAgent,
                evidenceCriticAgent,
                researchAssistantAgent,
                decisionAgent);
        this.researchTaskService = researchTaskService;
        this.objectMapper = objectMapper;
    }

    public AgentRunResult run(AgentContext context) {
        for (int step = context.resumeFromStep(); step <= MAX_STEPS && !context.finished(); step++) {
            if (context.taskId() != null) {
                researchTaskService.ensureActive(context.taskId());
            }
            ResearchAgent agent = nextAgent(context);
            AgentDecision decision = agent.act(context);
            context.addStep(AgentStep.of(step, agent.name(), decision));
            if (context.taskId() != null) {
                researchTaskService.saveCheckpoint(
                        context.taskId(),
                        step,
                        agent.name().name(),
                        context.currentStage(),
                        context.checkpointPayload());
            }
            if (decision.complete()) {
                context.finish();
            }
        }
        return AgentRunResult.from(context);
    }

    public AgentContext prepareContext(
            Long taskId,
            Long userId,
            Long projectId,
            String question,
            IntentType expectedIntent,
            Optional<ResearchTaskCheckpoint> latestCheckpoint
    ) {
        AgentContext context = new AgentContext(taskId, userId, projectId, question, question);
        if (expectedIntent != null) {
            context.setExpectedIntent(expectedIntent);
        }
        latestCheckpoint.ifPresent(checkpoint -> {
            AgentContextCheckpoint payload = readPayload(checkpoint.getResultJson());
            context.restoreFromCheckpoint(payload);
            context.setResumeFromStep(checkpoint.getStepNumber() + 1);
        });
        return context;
    }

    private ResearchAgent nextAgent(AgentContext context) {
        return agents.stream()
                .filter(agent -> agent.supports(context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No agent can handle current context."));
    }

    private AgentContextCheckpoint readPayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AgentContextCheckpoint.class);
        } catch (Exception exception) {
            return null;
        }
    }
}
