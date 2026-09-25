package com.mindbridge.agent.service.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.EvidenceStance;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import com.mindbridge.agent.service.agent.AgentContextCheckpoint;
import com.mindbridge.agent.service.agent.DecisionDraft;
import com.mindbridge.agent.service.project.ResearchProjectService;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 确定性决策校验：引用必须存在，属于当前项目或内置全局知识（projectId 为空），
 * 且出现在任务检索证据中。其他项目的切块一律拒绝。
 */
public class DecisionValidationService {

    private final ResearchProjectService researchProjectService;
    private final ResearchTaskService researchTaskService;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final ObjectMapper objectMapper;

    public DecisionValidationService(
            ResearchProjectService researchProjectService,
            ResearchTaskService researchTaskService,
            KnowledgeChunkRepository knowledgeChunkRepository,
            ObjectMapper objectMapper
    ) {
        this.researchProjectService = researchProjectService;
        this.researchTaskService = researchTaskService;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DecisionValidationResult validate(Long userId, Long projectId, Long taskId, DecisionDraft draft) {
        researchProjectService.requireOwnedProject(userId, projectId);
        ResearchTask task = researchTaskService.getRequired(taskId);
        if (!projectId.equals(task.projectId()) || (userId != null && !userId.equals(task.ownerId()))) {
            return DecisionValidationResult.invalid(List.of("task does not belong to current project"));
        }
        return validateInternal(projectId, taskId, draft);
    }

    /**
     * 测试与内部快捷入口：按项目校验草案引用。
     */
    @Transactional(readOnly = true)
    public DecisionValidationResult validate(Long projectId, DecisionDraft draft) {
        return validateInternal(projectId, null, draft);
    }

    public void validateOrThrow(Long projectId, DecisionDraft draft) {
        DecisionValidationResult result = validate(projectId, draft);
        if (!result.valid()) {
            throw new DecisionValidationException(String.join("; ", result.errors()));
        }
    }

    private DecisionValidationResult validateInternal(Long projectId, Long taskId, DecisionDraft draft) {
        List<String> errors = new ArrayList<>();
        if (draft == null) {
            return DecisionValidationResult.invalid(List.of("decision draft is required"));
        }
        if (isBlank(draft.recommendation())) {
            errors.add("recommendation is required");
        }
        if (isBlank(draft.minimumExperiment())) {
            errors.add("minimum experiment is required");
        }
        if (isBlank(draft.successCriteria())) {
            errors.add("measurable success criteria are required");
        }
        if (draft.confidence() < 0.0 || draft.confidence() > 1.0) {
            errors.add("confidence must be within 0-1");
        }
        boolean hasGaps = draft.evidenceGaps() != null && !draft.evidenceGaps().isEmpty();
        if (hasGaps && draft.confidence() >= 0.8) {
            errors.add("evidence gaps force confidence below 0.8");
        }

        Set<Long> retrievedChunkIds = loadRetrievedChunkIds(taskId);
        List<ValidatedCitation> supporting = validateCitations(
                projectId,
                draft.supportingChunkIds(),
                EvidenceStance.SUPPORT,
                retrievedChunkIds,
                taskId != null,
                errors);
        List<ValidatedCitation> opposing = validateCitations(
                projectId,
                draft.opposingChunkIds(),
                EvidenceStance.OPPOSE,
                retrievedChunkIds,
                taskId != null,
                errors);

        Set<Long> supportingIds = new HashSet<>();
        supporting.forEach(citation -> supportingIds.add(citation.chunkId()));
        for (ValidatedCitation oppose : opposing) {
            if (supportingIds.contains(oppose.chunkId())) {
                errors.add("citation chunk %d cannot be both supporting and opposing".formatted(oppose.chunkId()));
            }
        }

        if (!errors.isEmpty()) {
            return DecisionValidationResult.invalid(errors);
        }
        return new DecisionValidationResult(true, List.of(), supporting, opposing);
    }

    private List<ValidatedCitation> validateCitations(
            Long projectId,
            List<Long> chunkIds,
            EvidenceStance stance,
            Set<Long> retrievedChunkIds,
            boolean enforceRetrieved,
            List<String> errors
    ) {
        List<ValidatedCitation> citations = new ArrayList<>();
        if (chunkIds == null) {
            return citations;
        }
        Set<Long> seen = new HashSet<>();
        for (Long chunkId : chunkIds) {
            if (chunkId == null) {
                errors.add("citation id is required");
                continue;
            }
            if (!seen.add(chunkId)) {
                continue;
            }
            Optional<KnowledgeChunk> optional = knowledgeChunkRepository.findById(chunkId);
            if (optional.isEmpty()) {
                errors.add("citation chunk %d does not exist".formatted(chunkId));
                continue;
            }
            KnowledgeChunk chunk = optional.get();
            if (!visibleToProject(projectId, chunk.projectId())) {
                errors.add("citation chunk %d does not belong to current project".formatted(chunkId));
                continue;
            }
            if (enforceRetrieved && !retrievedChunkIds.contains(chunkId)) {
                errors.add("citation chunk %d was not retrieved in the current task evidence".formatted(chunkId));
                continue;
            }
            String excerpt = chunk.getContent();
            if (excerpt != null && excerpt.length() > 160) {
                excerpt = excerpt.substring(0, 160);
            }
            citations.add(new ValidatedCitation(chunkId, stance, chunk.getSource(), excerpt));
        }
        return citations;
    }

    private boolean visibleToProject(Long projectId, Long chunkProjectId) {
        return chunkProjectId == null || projectId.equals(chunkProjectId);
    }

    private Set<Long> loadRetrievedChunkIds(Long taskId) {
        if (taskId == null) {
            return Set.of();
        }
        Optional<ResearchTaskCheckpoint> latest = researchTaskService.latestCheckpoint(taskId);
        if (latest.isEmpty()) {
            return Set.of();
        }
        try {
            AgentContextCheckpoint payload = objectMapper.readValue(
                    latest.get().getResultJson(),
                    AgentContextCheckpoint.class);
            return payload.retrievedChunkIds() == null
                    ? Set.of()
                    : Set.copyOf(payload.retrievedChunkIds());
        } catch (Exception exception) {
            return Set.of();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
