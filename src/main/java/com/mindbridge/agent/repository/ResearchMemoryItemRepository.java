package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ResearchMemoryItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 项目级长期研究记忆访问。召回默认只取 active 条目。
 */
public interface ResearchMemoryItemRepository extends JpaRepository<ResearchMemoryItem, Long> {

    List<ResearchMemoryItem> findByProject_IdAndActiveTrueOrderByUpdatedAtDesc(Long projectId);

    List<ResearchMemoryItem> findTop8ByProject_IdAndActiveTrueOrderByUpdatedAtDesc(Long projectId);

    List<ResearchMemoryItem> findByProject_IdOrderByUpdatedAtDesc(Long projectId);
}
