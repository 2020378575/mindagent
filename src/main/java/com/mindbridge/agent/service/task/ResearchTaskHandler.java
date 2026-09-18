package com.mindbridge.agent.service.task;

import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.domain.ResearchTaskType;
import java.util.Optional;

/**
 * 单一任务类型的执行器。Registry 按 type 唯一注册。
 */
public interface ResearchTaskHandler {

    ResearchTaskType type();

    TaskExecutionResult execute(ResearchTask task, Optional<ResearchTaskCheckpoint> latestCheckpoint);
}
