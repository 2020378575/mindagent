package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ResearchMemoryItem;
import com.mindbridge.agent.domain.ResearchMemorySourceType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 项目级长期研究记忆访问。召回默认只取 active 条目。
 */
public interface ResearchMemoryItemRepository extends JpaRepository<ResearchMemoryItem, Long> {

    List<ResearchMemoryItem> findByProject_Id(Long projectId);

    List<ResearchMemoryItem> findByProject_IdAndActiveTrueOrderByUpdatedAtDesc(Long projectId);

    List<ResearchMemoryItem> findTop8ByProject_IdAndActiveTrueOrderByUpdatedAtDesc(Long projectId);

    List<ResearchMemoryItem> findByProject_IdOrderByUpdatedAtDesc(Long projectId);

    Optional<ResearchMemoryItem> findFirstByProject_IdAndSourceTypeAndSourceRecordIdAndActiveTrue(
            Long projectId,
            ResearchMemorySourceType sourceType,
            Long sourceRecordId
    );
}
