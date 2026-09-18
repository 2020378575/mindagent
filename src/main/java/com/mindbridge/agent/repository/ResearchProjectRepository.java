package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ResearchProject;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 研究项目数据访问。按所有者过滤，避免只凭项目 ID 读到别人的项目。
 */
public interface ResearchProjectRepository extends JpaRepository<ResearchProject, Long> {

    Optional<ResearchProject> findByIdAndOwner_Id(Long projectId, Long ownerId);

    List<ResearchProject> findByOwner_IdOrderByUpdatedAtDesc(Long ownerId);
}
