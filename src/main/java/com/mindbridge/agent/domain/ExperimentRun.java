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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(
        name = "experiment_runs",
        indexes = {
                @Index(name = "idx_experiment_project_decision", columnList = "project_id, decision_id"),
                @Index(name = "idx_experiment_status", columnList = "status")
        }
)
/**
 * 围绕已确认决策的最小验证实验。
 */
public class ExperimentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ResearchProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    @Column(nullable = false)
    private Long decisionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ExperimentStatus status = ExperimentStatus.PLANNED;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String hypothesis;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String setupNotes;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String metricsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String resultSummary;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Instant completedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ResearchProject getProject() {
        return project;
    }

    public void setProject(ResearchProject project) {
        this.project = project;
    }

    public Long projectId() {
        return project == null ? null : project.getId();
    }

    public UserAccount getOwner() {
        return owner;
    }

    public void setOwner(UserAccount owner) {
        this.owner = owner;
    }

    public Long getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(Long decisionId) {
        this.decisionId = decisionId;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public void setStatus(ExperimentStatus status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getHypothesis() {
        return hypothesis;
    }

    public void setHypothesis(String hypothesis) {
        this.hypothesis = hypothesis;
    }

    public String getSetupNotes() {
        return setupNotes;
    }

    public void setSetupNotes(String setupNotes) {
        this.setupNotes = setupNotes;
    }

    public String getMetricsJson() {
        return metricsJson;
    }

    public void setMetricsJson(String metricsJson) {
        this.metricsJson = metricsJson;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        touch();
    }
}
