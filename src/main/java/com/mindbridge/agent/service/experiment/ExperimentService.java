package com.mindbridge.agent.service.experiment;

import com.mindbridge.agent.domain.DecisionRecord;
import com.mindbridge.agent.domain.DecisionStatus;
import com.mindbridge.agent.domain.ExperimentRun;
import com.mindbridge.agent.domain.ExperimentStatus;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.dto.CompleteExperimentRequest;
import com.mindbridge.agent.dto.CreateExperimentRequest;
import com.mindbridge.agent.dto.CreateResearchTaskRequest;
import com.mindbridge.agent.repository.ExperimentRunRepository;
import com.mindbridge.agent.service.decision.DecisionService;
import com.mindbridge.agent.service.memory.ResearchLongTermMemoryService;
import com.mindbridge.agent.service.memory.ResearchProjectEvent;
import com.mindbridge.agent.service.project.ResearchProjectService;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * 实验记录服务。创建实验时可把已确认决策推进到 VALIDATING；完成后创建结果复核任务。
 */
public class ExperimentService {

    static final String EXPERIMENT_NOT_FOUND = "Experiment not found";

    private final ResearchProjectService researchProjectService;
    private final DecisionService decisionService;
    private final ExperimentRunRepository experimentRunRepository;
    private final ResearchLongTermMemoryService longTermMemoryService;
    private final ResearchTaskService researchTaskService;

    public ExperimentService(
            ResearchProjectService researchProjectService,
            DecisionService decisionService,
            ExperimentRunRepository experimentRunRepository,
            ResearchLongTermMemoryService longTermMemoryService,
            ResearchTaskService researchTaskService
    ) {
        this.researchProjectService = researchProjectService;
        this.decisionService = decisionService;
        this.experimentRunRepository = experimentRunRepository;
        this.longTermMemoryService = longTermMemoryService;
        this.researchTaskService = researchTaskService;
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
        if (decision.getStatus() != DecisionStatus.CONFIRMED
                && decision.getStatus() != DecisionStatus.VALIDATING
                && decision.getStatus() != DecisionStatus.REVIEWED) {
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
        if (decision.getStatus() == DecisionStatus.CONFIRMED || decision.getStatus() == DecisionStatus.REVIEWED) {
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

    /**
     * 实验完成后创建 RESULT_REVIEW 任务；幂等键按 experimentId 固定，避免重复提交。
     */
    @Transactional
    public ResearchTask createResultReviewTask(Long userId, Long projectId, ExperimentRun experiment) {
        if (experiment == null || experiment.getDecisionId() == null || experiment.getId() == null) {
            return null;
        }
        DecisionRecord decision = decisionService.requireOwned(userId, projectId, experiment.getDecisionId());
        if (decision.getStatus() != DecisionStatus.VALIDATING
                && decision.getStatus() != DecisionStatus.CONFIRMED
                && decision.getStatus() != DecisionStatus.REVIEWED) {
            return null;
        }
        String question = "对照实验「%s」结果复核决策：%s。实验结果摘要：%s".formatted(
                experiment.getTitle() == null ? ("#" + experiment.getId()) : experiment.getTitle(),
                decision.getRecommendation(),
                experiment.getResultSummary() == null ? "" : experiment.getResultSummary());
        return researchTaskService.create(
                userId,
                projectId,
                new CreateResearchTaskRequest(
                        "result-review-exp-" + experiment.getId(),
                        ResearchTaskType.RESULT_REVIEW,
                        question,
                        null,
                        experiment.getDecisionId(),
                        experiment.getId()));
    }
}
