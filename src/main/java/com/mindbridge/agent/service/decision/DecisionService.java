package com.mindbridge.agent.service.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.DecisionEvidence;
import com.mindbridge.agent.domain.DecisionRecord;
import com.mindbridge.agent.domain.DecisionReview;
import com.mindbridge.agent.domain.DecisionStatus;
import com.mindbridge.agent.domain.ExperimentRun;
import com.mindbridge.agent.domain.ExperimentStatus;
import com.mindbridge.agent.domain.MemoryValidationStatus;
import com.mindbridge.agent.domain.ResearchMemoryItem;
import com.mindbridge.agent.domain.ResearchMemorySourceType;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.domain.ReviewVerdict;
import com.mindbridge.agent.repository.DecisionEvidenceRepository;
import com.mindbridge.agent.repository.DecisionRecordRepository;
import com.mindbridge.agent.repository.DecisionReviewRepository;
import com.mindbridge.agent.repository.ExperimentRunRepository;
import com.mindbridge.agent.repository.ResearchMemoryItemRepository;
import com.mindbridge.agent.service.agent.AgentContextCheckpoint;
import com.mindbridge.agent.service.agent.DecisionDraft;
import com.mindbridge.agent.service.memory.ResearchLongTermMemoryService;
import com.mindbridge.agent.service.memory.ResearchProjectEvent;
import com.mindbridge.agent.service.memory.ValidatedResearchMemory;
import com.mindbridge.agent.service.project.ResearchProjectService;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 决策状态机：草稿 → 确认 → 实验验证 → 复核。仅确认/复核可晋升长期记忆。
 */
public class DecisionService {

    static final String DECISION_NOT_FOUND = "Decision not found";
    static final String INVALID_TRANSITION = "Invalid decision status transition";
    static final String IMMUTABLE_MESSAGE = "Confirmed decisions are immutable; regenerate a new version instead";

    private final ResearchProjectService researchProjectService;
    private final ResearchTaskService researchTaskService;
    private final DecisionValidationService validationService;
    private final DecisionRecordRepository decisionRecordRepository;
    private final DecisionEvidenceRepository decisionEvidenceRepository;
    private final DecisionReviewRepository decisionReviewRepository;
    private final ExperimentRunRepository experimentRunRepository;
    private final ResearchMemoryItemRepository researchMemoryItemRepository;
    private final ResearchLongTermMemoryService longTermMemoryService;
    private final ObjectMapper objectMapper;

    public DecisionService(
            ResearchProjectService researchProjectService,
            ResearchTaskService researchTaskService,
            DecisionValidationService validationService,
            DecisionRecordRepository decisionRecordRepository,
            DecisionEvidenceRepository decisionEvidenceRepository,
            DecisionReviewRepository decisionReviewRepository,
            ExperimentRunRepository experimentRunRepository,
            ResearchMemoryItemRepository researchMemoryItemRepository,
            ResearchLongTermMemoryService longTermMemoryService,
            ObjectMapper objectMapper
    ) {
        this.researchProjectService = researchProjectService;
        this.researchTaskService = researchTaskService;
        this.validationService = validationService;
        this.decisionRecordRepository = decisionRecordRepository;
        this.decisionEvidenceRepository = decisionEvidenceRepository;
        this.decisionReviewRepository = decisionReviewRepository;
        this.experimentRunRepository = experimentRunRepository;
        this.researchMemoryItemRepository = researchMemoryItemRepository;
        this.longTermMemoryService = longTermMemoryService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DecisionRecord requireOwned(Long userId, Long projectId, Long decisionId) {
        researchProjectService.requireOwnedProject(userId, projectId);
        return decisionRecordRepository.findByIdAndProject_IdAndOwner_Id(decisionId, projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException(DECISION_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<DecisionRecord> list(Long userId, Long projectId) {
        researchProjectService.requireOwnedProject(userId, projectId);
        return decisionRecordRepository.findByProject_IdAndOwner_IdOrderByUpdatedAtDesc(projectId, userId);
    }

    @Transactional
    public DecisionRecord createDraftFromTask(Long userId, Long projectId, Long taskId) {
        ResearchProject project = researchProjectService.requireOwnedProject(userId, projectId);
        ResearchTask task = researchTaskService.getRequired(taskId);
        if (!projectId.equals(task.projectId()) || !userId.equals(task.ownerId())) {
            throw new IllegalArgumentException(ResearchTaskService.TASK_NOT_FOUND_MESSAGE);
        }
        DecisionDraft draft = loadDraftFromTask(taskId);
        DecisionValidationResult validation = validationService.validate(userId, projectId, taskId, draft);
        if (!validation.valid()) {
            researchTaskService.markFailed(taskId, "DECISION_VALIDATION_FAILED", String.join("; ", validation.errors()));
            throw new DecisionValidationException(String.join("; ", validation.errors()));
        }
        DecisionRecord record = newDecision(project, taskId, null, 1, draft);
        DecisionRecord saved = decisionRecordRepository.save(record);
        persistEvidence(saved, validation);
        if (task.getStatus() == ResearchTaskStatus.WAITING_FOR_CONFIRMATION
                || task.getStatus() == ResearchTaskStatus.SUCCEEDED
                || task.getStatus() == ResearchTaskStatus.RUNNING) {
            researchTaskService.markWaitingForConfirmation(taskId, saved.getId());
        }
        longTermMemoryService.rememberProjectEvent(projectId, new ResearchProjectEvent(
                "DECISION_DRAFT",
                saved.getRecommendation(),
                saved.getId(),
                Instant.now()));
        return saved;
    }

    @Transactional
    public DecisionRecord confirm(Long userId, Long projectId, Long decisionId) {
        DecisionRecord decision = requireOwned(userId, projectId, decisionId);
        if (decision.getStatus() != DecisionStatus.DRAFT) {
            throw new IllegalStateException(INVALID_TRANSITION);
        }
        decision.setStatus(DecisionStatus.CONFIRMED);
        decision.setConfirmedAt(Instant.now());
        decision.touch();
        DecisionRecord saved = decisionRecordRepository.save(decision);
        longTermMemoryService.rememberValidated(new ValidatedResearchMemory(
                projectId,
                ResearchMemorySourceType.DECISION,
                saved.getId(),
                MemoryValidationStatus.CONFIRMED,
                saved.getRecommendation(),
                evidenceChunkIds(saved.getId())));
        if (saved.getTaskId() != null) {
            researchTaskService.markSucceeded(saved.getTaskId(), saved.getId());
        }
        longTermMemoryService.rememberProjectEvent(projectId, new ResearchProjectEvent(
                "DECISION_CONFIRMED",
                "v%d: %s".formatted(saved.getVersion(), saved.getRecommendation()),
                saved.getId(),
                Instant.now()));
        return saved;
    }

    @Transactional
    public DecisionRecord discard(Long userId, Long projectId, Long decisionId) {
        DecisionRecord decision = requireOwned(userId, projectId, decisionId);
        if (decision.getStatus() != DecisionStatus.DRAFT) {
            throw new IllegalStateException(INVALID_TRANSITION);
        }
        decision.setStatus(DecisionStatus.DISCARDED);
        decision.touch();
        DecisionRecord saved = decisionRecordRepository.save(decision);
        if (saved.getTaskId() != null) {
            ResearchTask task = researchTaskService.getRequired(saved.getTaskId());
            if (task.getStatus() == ResearchTaskStatus.WAITING_FOR_CONFIRMATION) {
                researchTaskService.cancel(userId, projectId, task.getPublicId());
            }
        }
        longTermMemoryService.rememberProjectEvent(projectId, new ResearchProjectEvent(
                "DECISION_DISCARDED",
                "v%d discarded: %s".formatted(saved.getVersion(), saved.getRecommendation()),
                saved.getId(),
                Instant.now()));
        return saved;
    }

    @Transactional
    public DecisionRecord regenerate(Long userId, Long projectId, Long previousDecisionId, Long taskId) {
        DecisionRecord previous = requireOwned(userId, projectId, previousDecisionId);
        if (previous.getStatus() == DecisionStatus.DRAFT) {
            throw new IllegalStateException("Cannot regenerate from an unconfirmed draft");
        }
        ResearchProject project = researchProjectService.requireOwnedProject(userId, projectId);
        ResearchTask task = researchTaskService.getRequired(taskId);
        if (!projectId.equals(task.projectId()) || !userId.equals(task.ownerId())) {
            throw new IllegalArgumentException(ResearchTaskService.TASK_NOT_FOUND_MESSAGE);
        }
        DecisionDraft draft = loadDraftFromTask(taskId);
        DecisionValidationResult validation = validationService.validate(userId, projectId, taskId, draft);
        if (!validation.valid()) {
            researchTaskService.markFailed(taskId, "DECISION_VALIDATION_FAILED", String.join("; ", validation.errors()));
            throw new DecisionValidationException(String.join("; ", validation.errors()));
        }
        DecisionRecord next = newDecision(project, taskId, previous.getId(), previous.getVersion() + 1, draft);
        DecisionRecord saved = decisionRecordRepository.save(next);
        persistEvidence(saved, validation);
        researchTaskService.markWaitingForConfirmation(taskId, saved.getId());
        return saved;
    }

    @Transactional
    public DecisionRecord startValidation(Long userId, Long projectId, Long decisionId, Long experimentId) {
        DecisionRecord decision = requireOwned(userId, projectId, decisionId);
        if (decision.getStatus() != DecisionStatus.CONFIRMED) {
            throw new IllegalStateException(INVALID_TRANSITION);
        }
        ExperimentRun experiment = experimentRunRepository.findByIdAndProject_IdAndOwner_Id(experimentId, projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Experiment not found"));
        if (!decisionId.equals(experiment.getDecisionId())) {
            throw new IllegalArgumentException("Experiment does not belong to this decision");
        }
        if (experiment.getStatus() != ExperimentStatus.PLANNED && experiment.getStatus() != ExperimentStatus.RUNNING) {
            throw new IllegalStateException("Experiment is not ready for validation");
        }
        decision.setStatus(DecisionStatus.VALIDATING);
        decision.setExperimentId(experimentId);
        decision.touch();
        if (experiment.getStatus() == ExperimentStatus.PLANNED) {
            experiment.setStatus(ExperimentStatus.RUNNING);
            experiment.touch();
            experimentRunRepository.save(experiment);
        }
        return decisionRecordRepository.save(decision);
    }

    @Transactional
    public DecisionReview confirmReview(Long userId, Long projectId, Long decisionId, Long reviewTaskId) {
        DecisionRecord decision = requireOwned(userId, projectId, decisionId);
        if (decision.getStatus() != DecisionStatus.VALIDATING) {
            throw new IllegalStateException(INVALID_TRANSITION);
        }
        if (decision.getExperimentId() == null) {
            throw new IllegalStateException("Decision has no linked experiment");
        }
        ResearchTask task = researchTaskService.getRequired(reviewTaskId);
        if (!projectId.equals(task.projectId())
                || !userId.equals(task.ownerId())
                || task.getType() != ResearchTaskType.RESULT_REVIEW) {
            throw new IllegalArgumentException("Review task not found");
        }
        ReviewVerdict verdict = resolveVerdict(task);
        String summary = resolveReviewSummary(task, decision);
        DecisionReview review = new DecisionReview();
        review.setDecision(decision);
        review.setExperimentId(decision.getExperimentId());
        review.setReviewTaskId(reviewTaskId);
        review.setVerdict(verdict);
        review.setSummary(summary);
        DecisionReview saved = decisionReviewRepository.save(review);

        decision.setStatus(DecisionStatus.REVIEWED);
        decision.touch();
        decisionRecordRepository.save(decision);
        researchTaskService.markSucceeded(reviewTaskId, saved.getId());

        applyMemoryForReview(projectId, decision, saved);
        longTermMemoryService.rememberProjectEvent(projectId, new ResearchProjectEvent(
                "DECISION_REVIEWED",
                "%s: %s".formatted(verdict.name(), summary),
                saved.getId(),
                Instant.now()));
        return saved;
    }

    public void assertMutable(DecisionRecord decision) {
        if (decision.getStatus() != DecisionStatus.DRAFT) {
            throw new IllegalStateException(IMMUTABLE_MESSAGE);
        }
    }

    private void applyMemoryForReview(Long projectId, DecisionRecord decision, DecisionReview review) {
        List<Long> chunkIds = evidenceChunkIds(decision.getId());
        switch (review.getVerdict()) {
            case SUPPORTED, PARTIALLY_SUPPORTED -> longTermMemoryService.rememberValidated(new ValidatedResearchMemory(
                    projectId,
                    ResearchMemorySourceType.EXPERIMENT_REVIEW,
                    review.getId(),
                    MemoryValidationStatus.EXPERIMENT_VERIFIED,
                    review.getSummary(),
                    chunkIds));
            case REFUTED -> researchMemoryItemRepository
                    .findFirstByProject_IdAndSourceTypeAndSourceRecordIdAndActiveTrue(
                            projectId,
                            ResearchMemorySourceType.DECISION,
                            decision.getId())
                    .ifPresent(item -> longTermMemoryService.markRefuted(item.getId(), review.getId()));
            case INCONCLUSIVE -> {
                // keep prior confirmed memory; no new promotion
            }
        }
    }

    private DecisionRecord newDecision(
            ResearchProject project,
            Long taskId,
            Long previousDecisionId,
            int version,
            DecisionDraft draft
    ) {
        DecisionRecord record = new DecisionRecord();
        record.setProject(project);
        record.setOwner(project.getOwner());
        record.setTaskId(taskId);
        record.setPreviousDecisionId(previousDecisionId);
        record.setVersion(version);
        record.setStatus(DecisionStatus.DRAFT);
        record.setQuestion(draft.question());
        record.setRecommendation(draft.recommendation().trim());
        record.setRationale(draft.rationale());
        record.setMinimumExperiment(draft.minimumExperiment().trim());
        record.setSuccessCriteria(draft.successCriteria().trim());
        record.setConfidence(draft.confidence());
        record.setOptionsJson(writeJson(draft.options()));
        record.setEvidenceGapsJson(writeJson(draft.evidenceGaps()));
        return record;
    }

    private void persistEvidence(DecisionRecord decision, DecisionValidationResult validation) {
        List<DecisionEvidence> rows = new ArrayList<>();
        for (ValidatedCitation citation : validation.supporting()) {
            rows.add(toEvidence(decision, citation));
        }
        for (ValidatedCitation citation : validation.opposing()) {
            rows.add(toEvidence(decision, citation));
        }
        decisionEvidenceRepository.saveAll(rows);
    }

    private DecisionEvidence toEvidence(DecisionRecord decision, ValidatedCitation citation) {
        DecisionEvidence evidence = new DecisionEvidence();
        evidence.setDecision(decision);
        evidence.setChunkId(citation.chunkId());
        evidence.setStance(citation.stance());
        evidence.setNote(citation.excerpt());
        return evidence;
    }

    private List<Long> evidenceChunkIds(Long decisionId) {
        return decisionEvidenceRepository.findByDecision_Id(decisionId).stream()
                .map(DecisionEvidence::getChunkId)
                .toList();
    }

    private DecisionDraft loadDraftFromTask(Long taskId) {
        Optional<ResearchTaskCheckpoint> latest = researchTaskService.latestCheckpoint(taskId);
        if (latest.isEmpty()) {
            throw new IllegalArgumentException("Decision draft checkpoint not found");
        }
        try {
            AgentContextCheckpoint payload = objectMapper.readValue(
                    latest.get().getResultJson(),
                    AgentContextCheckpoint.class);
            if (payload.decisionDraft() == null) {
                throw new IllegalArgumentException("Decision draft checkpoint not found");
            }
            return payload.decisionDraft();
        } catch (DecisionValidationException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Decision draft checkpoint not found");
        }
    }

    private ReviewVerdict resolveVerdict(ResearchTask task) {
        Optional<ResearchTaskCheckpoint> latest = researchTaskService.latestCheckpoint(task.getId());
        if (latest.isPresent()) {
            try {
                AgentContextCheckpoint payload = objectMapper.readValue(
                        latest.get().getResultJson(),
                        AgentContextCheckpoint.class);
                if (payload.decisionDraft() != null && payload.decisionDraft().recommendation() != null) {
                    String text = payload.decisionDraft().recommendation().toLowerCase(Locale.ROOT);
                    if (text.contains("refut") || text.contains("证伪") || text.contains("否定")) {
                        return ReviewVerdict.REFUTED;
                    }
                    if (text.contains("partial") || text.contains("部分")) {
                        return ReviewVerdict.PARTIALLY_SUPPORTED;
                    }
                    if (text.contains("inconclusive") || text.contains("不确定") || text.contains("不足")) {
                        return ReviewVerdict.INCONCLUSIVE;
                    }
                    if (text.contains("support") || text.contains("验证") || text.contains("支持")) {
                        return ReviewVerdict.SUPPORTED;
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return ReviewVerdict.INCONCLUSIVE;
    }

    private String resolveReviewSummary(ResearchTask task, DecisionRecord decision) {
        Optional<ResearchTaskCheckpoint> latest = researchTaskService.latestCheckpoint(task.getId());
        if (latest.isPresent()) {
            try {
                AgentContextCheckpoint payload = objectMapper.readValue(
                        latest.get().getResultJson(),
                        AgentContextCheckpoint.class);
                if (payload.assistantSummary() != null && !payload.assistantSummary().isBlank()) {
                    return payload.assistantSummary().trim();
                }
                if (payload.decisionDraft() != null && payload.decisionDraft().rationale() != null) {
                    return payload.decisionDraft().rationale().trim();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return "Review completed for decision v%d".formatted(decision.getVersion());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception exception) {
            return "[]";
        }
    }
}
