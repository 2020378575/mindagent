package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.KnowledgeChunk;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识库切块的数据访问接口。
 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByProject_Id(Long projectId);

    /** 内置/管理员全局知识块（project_id 为空），可被任意项目检索合并。 */
    List<KnowledgeChunk> findByProjectIsNull();

    List<KnowledgeChunk> findByResearchSource_IdOrderBySourceIndexAsc(Long sourceId);

    List<KnowledgeChunk> findByResearchSource_IdAndSourceIndexBetweenOrderBySourceIndexAsc(
            Long sourceId,
            int startIndex,
            int endIndex
    );

    void deleteByResearchSource_Id(Long sourceId);

    List<KnowledgeChunk> findTop20BySourceOrderByCreatedAtDesc(String source);

    /** 检索命中后取相邻切块，用于补齐全局知识库上下文。 */
    List<KnowledgeChunk> findBySourceAndSourceIndexBetweenOrderBySourceIndexAsc(
            String source,
            int startIndex,
            int endIndex
    );

    /** 判断内置或上传来源是否已经入库。 */
    long countBySource(String source);

    /** 同名文件重新上传时清理旧切块。 */
    void deleteBySource(String source);
}
