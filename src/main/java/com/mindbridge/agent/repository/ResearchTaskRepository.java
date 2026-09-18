package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 研究任务数据访问。按所有者和项目过滤，claim 必须原子更新状态。
 */
public interface ResearchTaskRepository extends JpaRepository<ResearchTask, Long> {

    Optional<ResearchTask> findByOwner_IdAndProject_IdAndIdempotencyKey(
            Long ownerId,
            Long projectId,
            String idempotencyKey
    );

    Optional<ResearchTask> findByPublicIdAndProject_IdAndOwner_Id(
            String publicId,
            Long projectId,
            Long ownerId
    );

    List<ResearchTask> findByStatus(ResearchTaskStatus status);

    List<ResearchTask> findByStatusAndUpdatedAtBefore(ResearchTaskStatus status, Instant updatedBefore);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ResearchTask t
               set t.status = com.mindbridge.agent.domain.ResearchTaskStatus.RUNNING,
                   t.attemptCount = t.attemptCount + 1,
                   t.startedAt = coalesce(t.startedAt, :now),
                   t.updatedAt = :now,
                   t.errorCode = null,
                   t.errorMessage = null
             where t.id = :taskId
               and t.status = com.mindbridge.agent.domain.ResearchTaskStatus.PENDING
            """)
    int claimPendingTask(@Param("taskId") Long taskId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ResearchTask t
               set t.status = com.mindbridge.agent.domain.ResearchTaskStatus.PENDING,
                   t.updatedAt = :now
             where t.status = com.mindbridge.agent.domain.ResearchTaskStatus.RUNNING
               and t.updatedAt < :staleBefore
            """)
    int resetStaleRunningTasks(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);
}
