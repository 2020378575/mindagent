package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.DecisionEvidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionEvidenceRepository extends JpaRepository<DecisionEvidence, Long> {

    List<DecisionEvidence> findByDecision_Id(Long decisionId);

    void deleteByDecision_Id(Long decisionId);
}
