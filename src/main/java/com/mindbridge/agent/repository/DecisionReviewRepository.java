package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.DecisionReview;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionReviewRepository extends JpaRepository<DecisionReview, Long> {

    List<DecisionReview> findByDecision_IdOrderByCreatedAtDesc(Long decisionId);

    Optional<DecisionReview> findFirstByDecision_IdOrderByCreatedAtDesc(Long decisionId);
}
