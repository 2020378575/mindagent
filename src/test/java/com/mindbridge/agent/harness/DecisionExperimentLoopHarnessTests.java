package com.mindbridge.agent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.DecisionRecord;
import com.mindbridge.agent.domain.DecisionReview;
import com.mindbridge.agent.domain.DecisionStatus;
import com.mindbridge.agent.domain.ExperimentRun;
import com.mindbridge.agent.domain.ExperimentStatus;
import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.domain.MemoryValidationStatus;
import com.mindbridge.agent.domain.ProjectStatus;
import com.mindbridge.agent.domain.ResearchMemoryItem;
import com.mindbridge.agent.domain.ResearchProject;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.domain.ReviewVerdict;
import com.mindbridge.agent.domain.UserAccount;
import com.mindbridge.agent.dto.CompleteExperimentRequest;
import com.mindbridge.agent.dto.CreateExperimentRequest;
import com.mindbridge.agent.repository.DecisionRecordRepository;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import com.mindbridge.agent.repository.ResearchMemoryItemRepository;
import com.mindbridge.agent.repository.ResearchProjectRepository;
import com.mindbridge.agent.repository.ResearchTaskCheckpointRepository;
import com.mindbridge.agent.repository.ResearchTaskRepository;
import com.mindbridge.agent.repository.UserAccountRepository;
import com.mindbridge.agent.service.agent.AgentContextCheckpoint;
import com.mindbridge.agent.service.agent.DecisionDraft;
import com.mindbridge.agent.service.agent.DecisionOption;
import com.mindbridge.agent.service.decision.DecisionService;
import com.mindbridge.agent.service.decision.DecisionValidationException;
import com.mindbridge.agent.service.decision.DecisionValidationService;
import com.mindbridge.agent.service.experiment.ExperimentService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mindbridge-decision-loop;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "mindbridge.knowledge.use-chroma=false",
        "mindbridge.memory.use-chroma=false",
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.client.enabled=false"
})
@Transactional
class DecisionExperimentLoopHarnessTests {

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private DecisionValidationService validationService;

    @Autowired
    private ExperimentService experimentService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private ResearchProjectRepository projectRepository;

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Autowired
    private ResearchTaskRepository taskRepository;

    @Autowired
    private ResearchTaskCheckpointRepository checkpointRepository;

    @Autowired
    private DecisionRecordRepository decisionRecordRepository;

    @Autowired
    private ResearchMemoryItemRepository researchMemoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UserAccount user;
    private ResearchProject projectA;
    private ResearchProject projectB;
    private KnowledgeChunk chunkA;
    private KnowledgeChunk chunkB;

    @BeforeEach
    void setUp() {
        user = new UserAccount();
        user.setUsername("decision-user-" + UUID.randomUUID());
        user.setDisplayName("Decision User");
        user.setPassword("secret");
        user.setRoles(Set.of("ROLE_USER"));
        user = userAccountRepository.save(user);

        projectA = saveProject("Project A");
        projectB = saveProject("Project B");
        chunkA = saveChunk(projectA, "qlora-paper.md", "QLoRA reduces peak VRAM with NF4.");
        chunkB = saveChunk(projectB, "foreign.md", "Foreign project evidence.");
    }

    @Test
    void rejectsForeignCitationAndPromotesMemoryOnlyAfterConfirm() throws Exception {
        DecisionDraft foreignDraft = draft(List.of(chunkB.getId()), List.of(), 0.6, List.of());
        assertThatThrownBy(() -> validationService.validateOrThrow(projectA.getId(), foreignDraft))
                .isInstanceOf(DecisionValidationException.class)
                .hasMessageContaining("citation");

        ResearchTask draftTask = saveTask(projectA, ResearchTaskType.DECISION, "12GB 显存该选 LoRA 还是 QLoRA？");
        saveDecisionCheckpoint(draftTask, draft(List.of(chunkA.getId()), List.of(), 0.7, List.of("gap")));

        assertThat(researchMemoryRepository.findByProject_Id(projectA.getId())).isEmpty();

        DecisionRecord draft = decisionService.createDraftFromTask(user.getId(), projectA.getId(), draftTask.getId());
        assertThat(draft.getStatus()).isEqualTo(DecisionStatus.DRAFT);
        assertThat(researchMemoryRepository.findByProject_Id(projectA.getId())).isEmpty();

        DecisionRecord v1 = decisionService.confirm(user.getId(), projectA.getId(), draft.getId());
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v1.getStatus()).isEqualTo(DecisionStatus.CONFIRMED);
        assertThat(researchMemoryRepository.findByProject_Id(projectA.getId())).hasSize(1);

        ResearchTask nextTask = saveTask(projectA, ResearchTaskType.DECISION, "再比较一次 QLoRA");
        saveDecisionCheckpoint(nextTask, draft(List.of(chunkA.getId()), List.of(), 0.65, List.of("still a gap")));
        DecisionRecord v2 = decisionService.regenerate(user.getId(), projectA.getId(), v1.getId(), nextTask.getId());

        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getStatus()).isEqualTo(DecisionStatus.DRAFT);
        assertThat(v2.getPreviousDecisionId()).isEqualTo(v1.getId());
        assertThat(decisionRecordRepository.findById(v1.getId())).get()
                .extracting(DecisionRecord::getStatus, DecisionRecord::getRecommendation)
                .containsExactly(DecisionStatus.CONFIRMED, v1.getRecommendation());
        assertThat(researchMemoryRepository.findByProject_Id(projectA.getId())).hasSize(1);
    }

    @Test
    void experimentReviewCanRefuteConfirmedMemory() throws Exception {
        ResearchTask draftTask = saveTask(projectA, ResearchTaskType.DECISION, "选 QLoRA");
        saveDecisionCheckpoint(draftTask, draft(List.of(chunkA.getId()), List.of(), 0.75, List.of()));
        DecisionRecord decision = decisionService.confirm(
                user.getId(),
                projectA.getId(),
                decisionService.createDraftFromTask(user.getId(), projectA.getId(), draftTask.getId()).getId());

        ExperimentRun experiment = experimentService.create(
                user.getId(),
                projectA.getId(),
                new CreateExperimentRequest(decision.getId(), "12GB smoke", "QLoRA fits", "batch=8"));
        assertThat(experiment.getStatus()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(decisionService.requireOwned(user.getId(), projectA.getId(), decision.getId()).getStatus())
                .isEqualTo(DecisionStatus.VALIDATING);

        experimentService.complete(
                user.getId(),
                projectA.getId(),
                experiment.getId(),
                new CompleteExperimentRequest("OOM on 12GB, decision refuted", "{\"peakGb\":14}"));

        ResearchTask reviewTask = saveTask(projectA, ResearchTaskType.RESULT_REVIEW, "run-019 是否验证了之前的决策？");
        saveDecisionCheckpoint(reviewTask, new DecisionDraft(
                reviewTask.getQuestion(),
                List.of(new DecisionOption("refute", "OOM", 0.9)),
                "实验结果证伪了之前的决策",
                "peak memory exceeded",
                List.of(chunkA.getId()),
                List.of(),
                List.of(),
                "retry with lower batch",
                "peak < 12GB",
                0.8));

        var review = decisionService.confirmReview(user.getId(), projectA.getId(), decision.getId(), reviewTask.getId());
        assertThat(review.getVerdict()).isEqualTo(ReviewVerdict.REFUTED);
        assertThat(decisionService.requireOwned(user.getId(), projectA.getId(), decision.getId()).getStatus())
                .isEqualTo(DecisionStatus.REVIEWED);

        List<ResearchMemoryItem> memories = researchMemoryRepository.findByProject_Id(projectA.getId());
        assertThat(memories).isNotEmpty();
        assertThat(memories.stream().filter(ResearchMemoryItem::isActive)).isEmpty();
        assertThat(memories)
                .anySatisfy(item -> assertThat(item.getValidationStatus()).isEqualTo(MemoryValidationStatus.REFUTED));
    }

    private ResearchProject saveProject(String name) {
        ResearchProject project = new ResearchProject();
        project.setOwner(user);
        project.setName(name);
        project.setObjective(name + " objective");
        project.setStatus(ProjectStatus.ACTIVE);
        return projectRepository.save(project);
    }

    private KnowledgeChunk saveChunk(ResearchProject project, String source, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setProject(project);
        chunk.setSource(source);
        chunk.setContent(content);
        chunk.setStartOffset(0);
        chunk.setEndOffset(content.length());
        return chunkRepository.save(chunk);
    }

    private ResearchTask saveTask(ResearchProject project, ResearchTaskType type, String question) {
        ResearchTask task = new ResearchTask();
        task.setPublicId(UUID.randomUUID().toString());
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task.setProject(project);
        task.setOwner(user);
        task.setType(type);
        task.setStatus(ResearchTaskStatus.WAITING_FOR_CONFIRMATION);
        task.setQuestion(question);
        task.setProgressPercent(90);
        return taskRepository.save(task);
    }

    private void saveDecisionCheckpoint(ResearchTask task, DecisionDraft draft) throws Exception {
        AgentContextCheckpoint payload = new AgentContextCheckpoint(
                ResearchTaskStage.DECISION,
                task.getType() == ResearchTaskType.RESULT_REVIEW
                        ? IntentType.RESULT_REVIEW
                        : IntentType.RESEARCH_DECISION,
                draft.supportingChunkIds(),
                null,
                draft,
                true,
                true,
                true,
                true,
                true,
                "query",
                draft.recommendation());
        ResearchTaskCheckpoint checkpoint = new ResearchTaskCheckpoint();
        checkpoint.setTask(task);
        checkpoint.setStepNumber(5);
        checkpoint.setActor("DECISION_AGENT");
        checkpoint.setStage(ResearchTaskStage.DECISION);
        checkpoint.setStatus(ResearchTaskStatus.SUCCEEDED);
        checkpoint.setResultJson(objectMapper.writeValueAsString(payload));
        checkpoint.setObservation("decision checkpoint");
        checkpointRepository.save(checkpoint);
    }

    private DecisionDraft draft(
            List<Long> supporting,
            List<Long> opposing,
            double confidence,
            List<String> gaps
    ) {
        return new DecisionDraft(
                "12GB 显存该选 LoRA 还是 QLoRA？",
                List.of(new DecisionOption("QLoRA", "lower peak memory", 0.8)),
                "QLoRA",
                "Evidence favors QLoRA under 12GB",
                supporting,
                opposing,
                gaps,
                "train one epoch on 12GB",
                "peak memory under 12GB",
                confidence);
    }
}
