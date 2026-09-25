package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.domain.ReviewVerdict;
import com.mindbridge.agent.service.task.TaskCheckpointPayload;
import java.util.List;

/**
 * 可序列化的 Agent 上下文检查点。不含 JPA 实体或任意 Map。
 */
public record AgentContextCheckpoint(
        ResearchTaskStage stage,
        IntentType intent,
        List<Long> retrievedChunkIds,
        EvidenceCritique critique,
        DecisionDraft decisionDraft,
        boolean contextLoaded,
        boolean intentRouted,
        boolean evidenceRetrieved,
        boolean evidenceCritiqued,
        boolean responseCompleted,
        String knowledgeQuery,
        String assistantSummary,
        ReviewVerdict reviewVerdict
) implements TaskCheckpointPayload {

    public AgentContextCheckpoint {
        retrievedChunkIds = retrievedChunkIds == null ? List.of() : List.copyOf(retrievedChunkIds);
    }
}
