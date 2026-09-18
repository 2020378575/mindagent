package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.IntentClassifier;
import org.springframework.stereotype.Component;

/**
 * 主控路由：把输入映射到研究意图路径。
 */
@Component
public class SupervisorAgent implements ResearchAgent {

    private final IntentClassifier intentClassifier;

    public SupervisorAgent(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }

    @Override
    public AgentName name() {
        return AgentName.SUPERVISOR_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.contextLoaded() && !context.intentRouted();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        IntentType intent = context.expectedIntent() != null
                ? context.expectedIntent()
                : intentClassifier.classify(context.modelInput()).intent();
        context.setIntent(intent);
        context.markIntentRouted();
        return AgentDecision.continueWith(AgentAction.ROUTE_INTENT, "intent=%s".formatted(intent));
    }
}
