package com.mindbridge.agent.service.task;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.service.agent.AgentContext;
import com.mindbridge.agent.service.agent.AgentRunResult;
import com.mindbridge.agent.service.agent.AgentRuntimeService;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class EvidenceQueryTaskHandler implements ResearchTaskHandler {

    private final AgentRuntimeService agentRuntimeService;

    public EvidenceQueryTaskHandler(AgentRuntimeService agentRuntimeService) {
        this.agentRuntimeService = agentRuntimeService;
    }

    @Override
    public ResearchTaskType type() {
        return ResearchTaskType.EVIDENCE_QUERY;
    }

    @Override
    public TaskExecutionResult execute(ResearchTask task, Optional<ResearchTaskCheckpoint> latestCheckpoint) {
        AgentContext context = agentRuntimeService.prepareContext(
                task.getId(),
                task.ownerId(),
                task.projectId(),
                task.getQuestion(),
                IntentType.EVIDENCE_QUERY,
                latestCheckpoint);
        AgentRunResult result = agentRuntimeService.run(context);
        if (result.intent() != IntentType.EVIDENCE_QUERY) {
            throw new IllegalStateException("Evidence query task routed to unexpected intent: " + result.intent());
        }
        return new TaskExecutionResult(ResearchTaskStatus.SUCCEEDED, null);
    }
}
