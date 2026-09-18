package com.mindbridge.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
        name = "decision_reviews",
        indexes = {
                @Index(name = "idx_decision_review_decision", columnList = "decision_id"),
                @Index(name = "idx_decision_review_experiment", columnList = "experiment_id")
        }
)
/**
 * 实验结果对决策的复核记录。
 */
public class DecisionReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id", nullable = false)
    private DecisionRecord decision;

    @Column(nullable = false)
    private Long experimentId;

    private Long reviewTaskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ReviewVerdict verdict;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String summary;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public DecisionRecord getDecision() {
        return decision;
    }

    public void setDecision(DecisionRecord decision) {
        this.decision = decision;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public Long getReviewTaskId() {
        return reviewTaskId;
    }

    public void setReviewTaskId(Long reviewTaskId) {
        this.reviewTaskId = reviewTaskId;
    }

    public ReviewVerdict getVerdict() {
        return verdict;
    }

    public void setVerdict(ReviewVerdict verdict) {
        this.verdict = verdict;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
