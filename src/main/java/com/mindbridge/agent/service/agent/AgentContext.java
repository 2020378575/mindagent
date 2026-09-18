package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.service.ai.AiMessage;
import com.mindbridge.agent.service.knowledge.SearchResult;
import com.mindbridge.agent.service.memory.ResearchMemoryBundle;
import java.util.ArrayList;
import java.util.List;

/**
 * 研究任务一轮 Agent 工作上下文。只持有 ID 与类型化摘要，不持有 JPA 实体。
 */
public class AgentContext {

    private final Long taskId;
    private final Long userId;
    private final Long projectId;
    private final String originalInput;
    private final String modelInput;
    private final List<AgentStep> steps = new ArrayList<>();

    private IntentType expectedIntent;
    private IntentType intent;
    private ResearchMemoryBundle memoryBundle;
    private List<SearchResult> retrievedEvidence = List.of();
    private EvidenceCritique critique;
    private DecisionDraft decisionDraft;
    private List<AiMessage> responseMessages = List.of();
    private String knowledgeQuery;
    private String responsePlan = "回答当前研究问题。";
    private AgentName responseAgent = AgentName.RESEARCH_ASSISTANT_AGENT;
    private int resumeFromStep = 1;

    private boolean contextLoaded;
    private boolean intentRouted;
    private boolean evidenceRetrieved;
    private boolean evidenceCritiqued;
    private boolean responseCompleted;
    private boolean finished;

    public AgentContext(Long taskId, Long userId, Long projectId, String originalInput, String modelInput) {
        this.taskId = taskId;
        this.userId = userId;
        this.projectId = projectId;
        this.originalInput = originalInput;
        this.modelInput = modelInput == null ? originalInput : modelInput;
    }

    public Long taskId() {
        return taskId;
    }

    public Long userId() {
        return userId;
    }

    public Long projectId() {
        return projectId;
    }

    public String originalInput() {
        return originalInput;
    }

    public String modelInput() {
        return modelInput;
    }

    public IntentType expectedIntent() {
        return expectedIntent;
    }

    public void setExpectedIntent(IntentType expectedIntent) {
        this.expectedIntent = expectedIntent;
    }

    public IntentType intent() {
        return intent;
    }

    public void setIntent(IntentType intent) {
        this.intent = intent;
    }

    public ResearchMemoryBundle memoryBundle() {
        return memoryBundle;
    }

    public void setMemoryBundle(ResearchMemoryBundle memoryBundle) {
        this.memoryBundle = memoryBundle;
    }

    public List<SearchResult> retrievedEvidence() {
        return retrievedEvidence;
    }

    public void setRetrievedEvidence(List<SearchResult> retrievedEvidence) {
        this.retrievedEvidence = retrievedEvidence == null ? List.of() : List.copyOf(retrievedEvidence);
    }

    public EvidenceCritique critique() {
        return critique;
    }

    public void setCritique(EvidenceCritique critique) {
        this.critique = critique;
    }

    public DecisionDraft decisionDraft() {
        return decisionDraft;
    }

    public void setDecisionDraft(DecisionDraft decisionDraft) {
        this.decisionDraft = decisionDraft;
    }

    public List<AiMessage> responseMessages() {
        return responseMessages;
    }

    public void setResponseMessages(List<AiMessage> responseMessages) {
        this.responseMessages = responseMessages == null ? List.of() : List.copyOf(responseMessages);
    }

    public String knowledgeQuery() {
        return knowledgeQuery;
    }

    public void setKnowledgeQuery(String knowledgeQuery) {
        this.knowledgeQuery = knowledgeQuery;
    }

    public String responsePlan() {
        return responsePlan;
    }

    public void setResponsePlan(String responsePlan) {
        this.responsePlan = responsePlan;
    }

    public AgentName responseAgent() {
        return responseAgent;
    }

    public void setResponseAgent(AgentName responseAgent) {
        this.responseAgent = responseAgent;
    }

    public String memoryBrief() {
        if (memoryBundle == null) {
            return "无研究记忆。";
        }
        int recent = memoryBundle.recentProjectEvents() == null ? 0 : memoryBundle.recentProjectEvents().size();
        int longTerm = memoryBundle.longTermMemories() == null ? 0 : memoryBundle.longTermMemories().size();
        String stage = memoryBundle.working() == null ? null : memoryBundle.working().currentStage();
        return "workingStage=%s recentEvents=%d longTerm=%d".formatted(
                stage == null ? "none" : stage, recent, longTerm);
    }

    public List<AgentStep> steps() {
        return List.copyOf(steps);
    }

    public void addStep(AgentStep step) {
        steps.add(step);
    }

    public int resumeFromStep() {
        return resumeFromStep;
    }

    public void setResumeFromStep(int resumeFromStep) {
        this.resumeFromStep = Math.max(1, resumeFromStep);
    }

    public boolean contextLoaded() {
        return contextLoaded;
    }

    public void markContextLoaded() {
        this.contextLoaded = true;
    }

    public boolean intentRouted() {
        return intentRouted;
    }

    public void markIntentRouted() {
        this.intentRouted = true;
    }

    public boolean evidenceRetrieved() {
        return evidenceRetrieved;
    }

    public void markEvidenceRetrieved() {
        this.evidenceRetrieved = true;
    }

    public boolean evidenceCritiqued() {
        return evidenceCritiqued;
    }

    public void markEvidenceCritiqued() {
        this.evidenceCritiqued = true;
    }

    public boolean responseCompleted() {
        return responseCompleted;
    }

    public void markResponseCompleted() {
        this.responseCompleted = true;
    }

    public boolean finished() {
        return finished;
    }

    public void finish() {
        this.finished = true;
    }

    public boolean needsEvidence() {
        return intent == IntentType.EVIDENCE_QUERY
                || intent == IntentType.RESEARCH_DECISION
                || intent == IntentType.RESULT_REVIEW;
    }

    public boolean needsCritique() {
        return intent == IntentType.RESEARCH_DECISION || intent == IntentType.RESULT_REVIEW;
    }

    public boolean needsDecision() {
        return needsCritique();
    }

    public ResearchTaskStage currentStage() {
        if (responseCompleted) {
            return intent == IntentType.RESULT_REVIEW ? ResearchTaskStage.REVIEW : ResearchTaskStage.DECISION;
        }
        if (evidenceCritiqued) {
            return ResearchTaskStage.CRITIC;
        }
        if (evidenceRetrieved) {
            return ResearchTaskStage.EVIDENCE;
        }
        if (intentRouted || contextLoaded) {
            return ResearchTaskStage.CONTEXT;
        }
        return ResearchTaskStage.CONTEXT;
    }

    public AgentContextCheckpoint checkpointPayload() {
        List<Long> chunkIds = retrievedEvidence.stream()
                .map(SearchResult::chunkId)
                .filter(id -> id != null)
                .toList();
        String assistantSummary = responseMessages.isEmpty()
                ? null
                : responseMessages.get(responseMessages.size() - 1).content();
        return new AgentContextCheckpoint(
                currentStage(),
                intent,
                chunkIds,
                critique,
                decisionDraft,
                contextLoaded,
                intentRouted,
                evidenceRetrieved,
                evidenceCritiqued,
                responseCompleted,
                knowledgeQuery,
                assistantSummary
        );
    }

    public void restoreFromCheckpoint(AgentContextCheckpoint checkpoint) {
        if (checkpoint == null) {
            return;
        }
        this.intent = checkpoint.intent();
        this.critique = checkpoint.critique();
        this.decisionDraft = checkpoint.decisionDraft();
        this.knowledgeQuery = checkpoint.knowledgeQuery();
        this.contextLoaded = checkpoint.contextLoaded();
        this.intentRouted = checkpoint.intentRouted();
        this.evidenceRetrieved = checkpoint.evidenceRetrieved();
        this.evidenceCritiqued = checkpoint.evidenceCritiqued();
        this.responseCompleted = checkpoint.responseCompleted();
        if (checkpoint.assistantSummary() != null && !checkpoint.assistantSummary().isBlank()) {
            this.responseMessages = List.of(AiMessage.assistant(checkpoint.assistantSummary()));
        }
        if (checkpoint.responseCompleted()) {
            this.finished = true;
        }
    }
}
