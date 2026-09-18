package com.mindbridge.agent.service.experiment;

import com.mindbridge.agent.domain.DecisionRecord;
import com.mindbridge.agent.domain.DecisionStatus;
import com.mindbridge.agent.domain.ExperimentRun;
import com.mindbridge.agent.domain.ExperimentStatus;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.dto.CompleteExperimentRequest;
import com.mindbridge.agent.dto.CreateExperimentRequest;
import com.mindbridge.agent.repository.ExperimentRunRepository;
import com.mindbridge.agent.service.decision.DecisionService;
import com.mindbridge.agent.service.memory.ResearchLongTermMemoryService;
import com.mindbridge.agent.service.memory.ResearchProjectEvent;
import com.mindbridge.agent.service.project.ResearchProjectService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 实验记录服务。创建实验时可把已确认决策推进到 VALIDATING。
 */
public class ExperimentService {

    static final String EXPERIMENT_NOT_FOUND = "Experiment not found";

    private final ResearchProjectService researchProjectService;
    private final DecisionService decisionService;
    private final ExperimentRunRepository experimentRunRepository;
    private final ResearchLongTermMemoryService longTermMemoryService;

    public ExperimentService(
            ResearchProjectService researchProjectService,
            DecisionService decisionService,
            ExperimentRunRepository experimentRunRepository,
            ResearchLongTermMemoryService longTermMemoryService
    ) {
        this.researchProjectService = researchProjectService;
        this.decisionService = decisionService;
        this.experimentRunRepository = experimentRunRepository;
        this.longTermMemoryService = longTermMemoryService;
    }

    @Transactional(readOnly = true)
    public ExperimentRun requireOwned(Long userId, Long projectId, Long experimentId) {
        researchProjectService.requireOwnedProject(userId, projectId);
        return experimentRunRepository.findByIdAndProject_IdAndOwner_Id(experimentId, projectId, userId)
                .orElseThrow(() -> new IllegalArgumentException(EXPERIMENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<ExperimentRun> list(Long userId, Long projectId) {
        researchProjectService.requireOwnedProject(userId, projectId);
        return experimentRunRepository.findByProject_IdAndOwner_IdOrderByUpdatedAtDesc(projectId, userId);
    }

    @Transactional
    public ExperimentRun create(Long userId, Long projectId, CreateExperimentRequest request) {
        ResearchProject project = researchProjectService.requireOwnedProject(userId, projectId);
        DecisionRecord decision = decisionService.requireOwned(userId, projectId, request.decisionId());
        if (decision.getStatus() != DecisionStatus.CONFIRMED && decision.getStatus() != DecisionStatus.VALIDATING) {
            throw new IllegalStateException("Experiments can only be created for confirmed decisions");
        }
        ExperimentRun run = new ExperimentRun();
        run.setProject(project);
        run.setOwner(project.getOwner());
        run.setDecisionId(decision.getId());
        run.setStatus(ExperimentStatus.PLANNED);
        run.setTitle(request.title().trim());
        run.setHypothesis(request.hypothesis());
        run.setSetupNotes(request.setupNotes());
        ExperimentRun saved = experimentRunRepository.save(run);
        if (decision.getStatus() == DecisionStatus.CONFIRMED) {
            decisionService.startValidation(userId, projectId, decision.getId(), saved.getId());
        }
        longTermMemoryService.rememberProjectEvent(projectId, new ResearchProjectEvent(
                "EXPERIMENT_CREATED",
                saved.getTitle(),
                saved.getId(),
                Instant.now()));
        return experimentRunRepository.findById(saved.getId()).orElse(saved);
    }

    @Transactional
    public ExperimentRun complete(Long userId, Long projectId, Long experimentId, CompleteExperimentRequest request) {
        ExperimentRun run = requireOwned(userId, projectId, experimentId);
        if (run.getStatus() == ExperimentStatus.COMPLETED) {
            return run;
        }
        if (run.getStatus() == ExperimentStatus.FAILED) {
            throw new IllegalStateException("Failed experiment cannot be completed");
        }
        run.setStatus(ExperimentStatus.COMPLETED);
        run.setMetricsJson(request.metricsJson());
        run.setResultSummary(request.resultSummary().trim());
        run.setCompletedAt(Instant.now());
        run.touch();
        ExperimentRun saved = experimentRunRepository.save(run);
        longTermMemoryService.rememberProjectEvent(projectId, new ResearchProjectEvent(
                "EXPERIMENT_COMPLETED",
                saved.getResultSummary(),
                saved.getId(),
                Instant.now()));
        return saved;
    }
}
