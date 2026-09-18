package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.MemoryValidationStatus;
import com.mindbridge.agent.domain.ResearchMemoryItem;
import com.mindbridge.agent.domain.ResearchMemoryType;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.repository.ResearchMemoryItemRepository;
import com.mindbridge.agent.repository.ResearchProjectRepository;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 长期研究记忆：仅接受已校验命令对象，召回排除 REFUTED，并按项目隔离。
 */
public class ResearchLongTermMemoryService {

    static final String MEMORY_NOT_FOUND_MESSAGE = "Research memory not found";
    static final String PROJECT_NOT_FOUND_MESSAGE = "Research project not found";
    static final int LONG_TERM_LIMIT = 8;

    private final ResearchProjectService researchProjectService;
    private final ResearchProjectRepository researchProjectRepository;
    private final ResearchMemoryItemRepository researchMemoryItemRepository;
    private final ResearchMemoryChromaGateway researchMemoryChromaGateway;
    private final ResearchWorkingMemoryService workingMemoryService;
    private final ProjectRecentMemoryService recentMemoryService;
    private final ObjectMapper objectMapper;

    public ResearchLongTermMemoryService(
            ResearchProjectService researchProjectService,
            ResearchProjectRepository researchProjectRepository,
            ResearchMemoryItemRepository researchMemoryItemRepository,
            ResearchMemoryChromaGateway researchMemoryChromaGateway,
            ResearchWorkingMemoryService workingMemoryService,
            ProjectRecentMemoryService recentMemoryService,
            ObjectMapper objectMapper
    ) {
        this.researchProjectService = researchProjectService;
        this.researchProjectRepository = researchProjectRepository;
        this.researchMemoryItemRepository = researchMemoryItemRepository;
        this.researchMemoryChromaGateway = researchMemoryChromaGateway;
        this.workingMemoryService = workingMemoryService;
        this.recentMemoryService = recentMemoryService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ResearchMemoryBundle load(Long userId, Long projectId, Long taskId, String query) {
        researchProjectService.requireOwnedProject(userId, projectId);
        ResearchWorkingMemory working = workingMemoryService.load(userId, projectId, taskId);
        List<String> recent = recentMemoryService.recentEvents(projectId);
        List<ResearchMemoryMatch> longTerm = recall(projectId, query);
        return new ResearchMemoryBundle(working, recent, longTerm);
    }

    public void rememberProjectEvent(Long projectId, ResearchProjectEvent event) {
        recentMemoryService.rememberProjectEvent(projectId, event);
    }

    @Transactional
    public ResearchMemoryItem rememberValidated(ValidatedResearchMemory memory) {
        ResearchProject project = researchProjectRepository.findById(memory.projectId())
                .orElseThrow(() -> new IllegalArgumentException(PROJECT_NOT_FOUND_MESSAGE));
        ResearchMemoryItem item = new ResearchMemoryItem();
        item.setProject(project);
        item.setOwner(project.getOwner());
        item.setType(inferType(memory));
        item.setSourceType(memory.sourceType());
        item.setSourceRecordId(memory.sourceRecordId());
        item.setValidationStatus(memory.validationStatus());
        item.setSummary(memory.summary().trim());
        item.setEvidenceChunkIdsJson(writeChunkIds(memory.evidenceChunkIds()));
        item.setActive(true);
        ResearchMemoryItem saved = researchMemoryItemRepository.save(item);
        researchMemoryChromaGateway.mirror(saved);
        return saved;
    }

    @Transactional
    public void markRefuted(Long memoryId, Long reviewId) {
        ResearchMemoryItem memory = researchMemoryItemRepository.findById(memoryId)
                .orElseThrow(() -> new IllegalArgumentException(MEMORY_NOT_FOUND_MESSAGE));
        memory.setValidationStatus(MemoryValidationStatus.REFUTED);
        memory.setActive(false);
        memory.setReviewId(reviewId);
        memory.touch();
        researchMemoryItemRepository.save(memory);
        researchMemoryChromaGateway.delete(memoryId);
    }

    @Transactional(readOnly = true)
    public List<ResearchMemoryMatch> recall(Long projectId, String query) {
        List<ResearchMemoryMatch> semantic = researchMemoryChromaGateway.query(projectId, query, LONG_TERM_LIMIT)
                .stream()
                .filter(match -> projectId.equals(match.projectId()))
                .filter(match -> match.validationStatus() != MemoryValidationStatus.REFUTED)
                .limit(LONG_TERM_LIMIT)
                .toList();
        if (!semantic.isEmpty()) {
            return semantic;
        }
        return researchMemoryItemRepository.findTop8ByProject_IdAndActiveTrueOrderByUpdatedAtDesc(projectId)
                .stream()
                .filter(item -> item.getValidationStatus() != MemoryValidationStatus.REFUTED)
                .filter(ResearchMemoryItem::isActive)
                .filter(item -> projectId.equals(item.projectId()))
                .map(item -> new ResearchMemoryMatch(
                        item.getId(),
                        item.projectId(),
                        item.getSummary(),
                        item.getValidationStatus(),
                        0.0))
                .toList();
    }

    private ResearchMemoryType inferType(ValidatedResearchMemory memory) {
        return switch (memory.sourceType()) {
            case DECISION -> ResearchMemoryType.METHOD_CHOICE;
            case EXPERIMENT_REVIEW -> ResearchMemoryType.FINDING;
        };
    }

    private String writeChunkIds(List<Long> chunkIds) {
        try {
            return objectMapper.writeValueAsString(chunkIds == null ? List.of() : chunkIds);
        } catch (Exception exception) {
            return "[]";
        }
    }
}
