package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 研究任务检查点数据访问。按 stepNumber 顺序读取，恢复时取最新成功点。
 */
public interface ResearchTaskCheckpointRepository extends JpaRepository<ResearchTaskCheckpoint, Long> {

    List<ResearchTaskCheckpoint> findByTask_IdOrderByStepNumberAsc(Long taskId);

    Optional<ResearchTaskCheckpoint> findFirstByTask_IdOrderByStepNumberDesc(Long taskId);
}
