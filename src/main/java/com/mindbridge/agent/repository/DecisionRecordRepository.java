package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.DecisionRecord;
import com.mindbridge.agent.domain.DecisionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRecordRepository extends JpaRepository<DecisionRecord, Long> {

    Optional<DecisionRecord> findByIdAndProject_IdAndOwner_Id(Long id, Long projectId, Long ownerId);

    List<DecisionRecord> findByProject_IdAndOwner_IdOrderByUpdatedAtDesc(Long projectId, Long ownerId);

    List<DecisionRecord> findByProject_IdAndStatusOrderByVersionDesc(Long projectId, DecisionStatus status);

    Optional<DecisionRecord> findFirstByProject_IdAndPreviousDecisionIdOrderByVersionDesc(
            Long projectId,
            Long previousDecisionId
    );
}
