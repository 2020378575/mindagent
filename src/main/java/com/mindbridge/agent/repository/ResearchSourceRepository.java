package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ResearchSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 研究资料数据访问。按项目和所有者同时过滤，避免跨项目读到别人的文件记录。
 */
public interface ResearchSourceRepository extends JpaRepository<ResearchSource, Long> {

    Optional<ResearchSource> findByIdAndProject_Id(Long sourceId, Long projectId);

    Optional<ResearchSource> findByIdAndProject_IdAndOwner_Id(Long sourceId, Long projectId, Long ownerId);

    Optional<ResearchSource> findByFilenameAndProject_Id(String filename, Long projectId);

    List<ResearchSource> findByProject_IdAndOwner_IdOrderByCreatedAtDesc(Long projectId, Long ownerId);
}
