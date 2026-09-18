package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.service.memory.ResearchLongTermMemoryService;
import com.mindbridge.agent.service.memory.ResearchMemoryBundle;
import com.mindbridge.agent.service.memory.ResearchWorkingMemory;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 加载三层研究记忆，作为后续 Agent 的共享上下文。
 */
@Component
public class ResearchContextAgent implements ResearchAgent {

    private final ResearchLongTermMemoryService longTermMemoryService;

    public ResearchContextAgent(ResearchLongTermMemoryService longTermMemoryService) {
        this.longTermMemoryService = longTermMemoryService;
    }

    @Override
    public AgentName name() {
        return AgentName.RESEARCH_CONTEXT_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return !context.contextLoaded();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        ResearchMemoryBundle bundle;
        try {
            bundle = longTermMemoryService.load(
                    context.userId(),
                    context.projectId(),
                    context.taskId(),
                    context.modelInput());
        } catch (Exception exception) {
            bundle = new ResearchMemoryBundle(
                    new ResearchWorkingMemory(
                            context.taskId(),
                            context.projectId(),
                            null,
                            context.originalInput(),
                            List.of(),
                            null,
                            null),
                    List.of(),
                    List.of());
        }
        context.setMemoryBundle(bundle);
        context.markContextLoaded();
        return AgentDecision.continueWith(
                AgentAction.LOAD_RESEARCH_CONTEXT,
                context.memoryBrief());
    }
}
