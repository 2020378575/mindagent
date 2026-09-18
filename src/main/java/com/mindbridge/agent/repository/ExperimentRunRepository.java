package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ExperimentRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentRunRepository extends JpaRepository<ExperimentRun, Long> {

    Optional<ExperimentRun> findByIdAndProject_IdAndOwner_Id(Long id, Long projectId, Long ownerId);

    List<ExperimentRun> findByProject_IdAndOwner_IdOrderByUpdatedAtDesc(Long projectId, Long ownerId);

    List<ExperimentRun> findByDecisionIdOrderByCreatedAtDesc(Long decisionId);
}
