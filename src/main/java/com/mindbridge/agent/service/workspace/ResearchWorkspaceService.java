package com.mindbridge.agent.service.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.DecisionEvidence;
import com.mindbridge.agent.domain.DecisionRecord;
import com.mindbridge.agent.domain.DecisionStatus;
import com.mindbridge.agent.domain.EvidenceStance;
import com.mindbridge.agent.domain.ExperimentRun;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.dto.DecisionResponse;
import com.mindbridge.agent.dto.ExperimentResponse;
import com.mindbridge.agent.dto.ResearchProjectResponse;
import com.mindbridge.agent.dto.ResearchSourceResponse;
import com.mindbridge.agent.dto.ResearchTaskResponse;
import com.mindbridge.agent.dto.ResearchWorkspaceResponse;
import com.mindbridge.agent.dto.WorkspaceActiveDecision;
import com.mindbridge.agent.repository.DecisionEvidenceRepository;
import com.mindbridge.agent.repository.ExperimentRunRepository;
import com.mindbridge.agent.service.agent.AgentContextCheckpoint;
import com.mindbridge.agent.service.decision.DecisionService;
import com.mindbridge.agent.service.document.ResearchSourceService;
import com.mindbridge.agent.service.experiment.ExperimentService;
import com.mindbridge.agent.service.project.ResearchProjectService;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 决策优先工作区聚合读取。
 */
public class ResearchWorkspaceService {

    private final ResearchProjectService researchProjectService;
    private final DecisionService decisionService;
    private final ExperimentService experimentService;
    private final ResearchSourceService researchSourceService;
    private final ResearchTaskService researchTaskService;
    private final DecisionEvidenceRepository decisionEvidenceRepository;
    private final ExperimentRunRepository experimentRunRepository;
    private final ObjectMapper objectMapper;

    public ResearchWorkspaceService(
            ResearchProjectService researchProjectService,
            DecisionService decisionService,
            ExperimentService experimentService,
            ResearchSourceService researchSourceService,
            ResearchTaskService researchTaskService,
            DecisionEvidenceRepository decisionEvidenceRepository,
            ExperimentRunRepository experimentRunRepository,
            ObjectMapper objectMapper
    ) {
        this.researchProjectService = researchProjectService;
        this.decisionService = decisionService;
        this.experimentService = experimentService;
        this.researchSourceService = researchSourceService;
        this.researchTaskService = researchTaskService;
        this.decisionEvidenceRepository = decisionEvidenceRepository;
        this.experimentRunRepository = experimentRunRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ResearchWorkspaceResponse load(Long userId, Long projectId) {
        var project = researchProjectService.requireOwnedProject(userId, projectId);
        List<DecisionRecord> decisions = decisionService.list(userId, projectId);
        List<ExperimentRun> experiments = experimentService.list(userId, projectId);
        WorkspaceActiveDecision active = decisions.stream()
                .filter(decision -> decision.getStatus() != DecisionStatus.DRAFT
                        && decision.getStatus() != DecisionStatus.DISCARDED)
                .findFirst()
                .map(this::toActiveDecision)
                .orElse(null);
        return new ResearchWorkspaceResponse(
                ResearchProjectResponse.from(project),
                active,
                decisions.stream().map(DecisionResponse::from).toList(),
                experiments.stream().map(ExperimentResponse::from).toList(),
                researchSourceService.list(userId, projectId).stream().map(ResearchSourceResponse::from).toList(),
                researchTaskService.list(userId, projectId).stream()
                        .limit(20)
                        .map(this::toTaskResponse)
                        .toList()
        );
    }

    private ResearchTaskResponse toTaskResponse(ResearchTask task) {
        ResearchTaskResponse response = ResearchTaskResponse.from(task);
        if (task.getType() != ResearchTaskType.RESULT_REVIEW) {
            return response;
        }
        return researchTaskService.latestCheckpoint(task.getId())
                .map(checkpoint -> reviewPreview(response, checkpoint.getResultJson()))
                .orElse(response);
    }

    private ResearchTaskResponse reviewPreview(ResearchTaskResponse response, String resultJson) {
        try {
            AgentContextCheckpoint payload = objectMapper.readValue(resultJson, AgentContextCheckpoint.class);
            String verdict = payload.reviewVerdict() == null ? null : payload.reviewVerdict().name();
            String summary = payload.assistantSummary();
            if ((summary == null || summary.isBlank()) && payload.decisionDraft() != null) {
                summary = payload.decisionDraft().recommendation();
            }
            return response.withReview(verdict, summary);
        } catch (Exception exception) {
            return response;
        }
    }

    private WorkspaceActiveDecision toActiveDecision(DecisionRecord decision) {
        List<DecisionEvidence> evidence = decisionEvidenceRepository.findByDecision_Id(decision.getId());
        int supporting = (int) evidence.stream().filter(item -> item.getStance() == EvidenceStance.SUPPORT).count();
        int opposing = (int) evidence.stream().filter(item -> item.getStance() == EvidenceStance.OPPOSE).count();
        ExperimentRun linked = null;
        if (decision.getExperimentId() != null) {
            linked = experimentRunRepository.findById(decision.getExperimentId()).orElse(null);
        }
        return new WorkspaceActiveDecision(
                decision.getId(),
                decision.getVersion(),
                decision.getStatus(),
                decision.getQuestion(),
                decision.getRecommendation(),
                decision.getConfidence(),
                supporting,
                opposing,
                readGaps(decision.getEvidenceGapsJson()),
                decision.getMinimumExperiment(),
                decision.getSuccessCriteria(),
                decision.getExperimentId(),
                linked == null ? null : linked.getStatus()
        );
    }

    private List<String> readGaps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }
}
