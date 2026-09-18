package com.mindbridge.agent.service.task;

import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.domain.ResearchTaskStage;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import com.mindbridge.agent.domain.ResearchTaskType;
import com.mindbridge.agent.service.document.ParsedDocument;
import com.mindbridge.agent.service.document.ResearchSourceService;
import com.mindbridge.agent.service.knowledge.ProjectKnowledgeService;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
/**
 * 资料入库任务：解析 → 项目级索引。从最近成功 checkpoint 继续。
 */
public class SourceIngestionTaskHandler implements ResearchTaskHandler {

    static final String ACTOR = "SOURCE_INGESTION_HANDLER";

    private final ResearchTaskService researchTaskService;
    private final ResearchSourceService researchSourceService;
    private final ProjectKnowledgeService projectKnowledgeService;

    public SourceIngestionTaskHandler(
            ResearchTaskService researchTaskService,
            ResearchSourceService researchSourceService,
            ProjectKnowledgeService projectKnowledgeService
    ) {
        this.researchTaskService = researchTaskService;
        this.researchSourceService = researchSourceService;
        this.projectKnowledgeService = projectKnowledgeService;
    }

    @Override
    public ResearchTaskType type() {
        return ResearchTaskType.SOURCE_INGESTION;
    }

    @Override
    public TaskExecutionResult execute(ResearchTask task, Optional<ResearchTaskCheckpoint> latestCheckpoint) {
        Long sourceId = task.getSourceId();
        if (sourceId == null) {
            throw new IllegalArgumentException("SOURCE_INGESTION requires sourceId");
        }
        ResearchTaskStage completed = latestCheckpoint.map(ResearchTaskCheckpoint::getStage).orElse(null);
        if (completed == ResearchTaskStage.INDEXING) {
            return new TaskExecutionResult(ResearchTaskStatus.SUCCEEDED, sourceId);
        }

        Long userId = task.ownerId();
        Long projectId = task.projectId();
        int step = latestCheckpoint.map(checkpoint -> checkpoint.getStepNumber()).orElse(0);

        if (completed == null) {
            researchTaskService.ensureActive(task.getId());
            researchTaskService.saveCheckpoint(
                    task.getId(),
                    ++step,
                    ACTOR,
                    ResearchTaskStage.SOURCE_STORAGE,
                    new SourceIngestionCheckpoint(ResearchTaskStage.SOURCE_STORAGE, sourceId, 0, 0, false)
            );
            completed = ResearchTaskStage.SOURCE_STORAGE;
        }

        ParsedDocument parsed = null;
        int sectionCount = 0;
        if (completed.ordinal() < ResearchTaskStage.PARSING.ordinal()) {
            researchTaskService.ensureActive(task.getId());
            parsed = researchSourceService.parse(userId, projectId, sourceId);
            sectionCount = parsed.sections().size();
            researchTaskService.saveCheckpoint(
                    task.getId(),
                    ++step,
                    ACTOR,
                    ResearchTaskStage.PARSING,
                    new SourceIngestionCheckpoint(ResearchTaskStage.PARSING, sourceId, sectionCount, 0, false)
            );
            completed = ResearchTaskStage.PARSING;
        }

        if (completed.ordinal() < ResearchTaskStage.INDEXING.ordinal()) {
            researchTaskService.ensureActive(task.getId());
            if (parsed == null) {
                parsed = researchSourceService.parse(userId, projectId, sourceId);
                sectionCount = parsed.sections().size();
            }
            int chunkCount;
            try {
                chunkCount = projectKnowledgeService.ingest(projectId, sourceId, parsed);
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new TransientTaskException("Indexing failed: " + exception.getMessage(), exception);
            }
            researchTaskService.saveCheckpoint(
                    task.getId(),
                    ++step,
                    ACTOR,
                    ResearchTaskStage.INDEXING,
                    new SourceIngestionCheckpoint(
                            ResearchTaskStage.INDEXING, sourceId, sectionCount, chunkCount, true)
            );
        }
        return new TaskExecutionResult(ResearchTaskStatus.SUCCEEDED, sourceId);
    }
}
