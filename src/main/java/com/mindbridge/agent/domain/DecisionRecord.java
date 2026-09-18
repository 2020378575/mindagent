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
        name = "decision_records",
        indexes = {
                @Index(name = "idx_decision_project_status", columnList = "project_id, status"),
                @Index(name = "idx_decision_project_version", columnList = "project_id, version")
        }
)
/**
 * 项目级可版本化决策。CONFIRMED 之后正文不可变，只能通过 regenerate 产生新版本。
 */
public class DecisionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ResearchProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    private Long taskId;

    private Long previousDecisionId;

    private Long experimentId;

    @Column(nullable = false)
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private DecisionStatus status = DecisionStatus.DRAFT;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String question;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String recommendation;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String rationale;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String minimumExperiment;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String successCriteria;

    @Column(nullable = false)
    private double confidence;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String optionsJson;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String evidenceGapsJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Instant confirmedAt;

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

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getPreviousDecisionId() {
        return previousDecisionId;
    }

    public void setPreviousDecisionId(Long previousDecisionId) {
        this.previousDecisionId = previousDecisionId;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public DecisionStatus getStatus() {
        return status;
    }

    public void setStatus(DecisionStatus status) {
        this.status = status;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public String getMinimumExperiment() {
        return minimumExperiment;
    }

    public void setMinimumExperiment(String minimumExperiment) {
        this.minimumExperiment = minimumExperiment;
    }

    public String getSuccessCriteria() {
        return successCriteria;
    }

    public void setSuccessCriteria(String successCriteria) {
        this.successCriteria = successCriteria;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getOptionsJson() {
        return optionsJson;
    }

    public void setOptionsJson(String optionsJson) {
        this.optionsJson = optionsJson;
    }

    public String getEvidenceGapsJson() {
        return evidenceGapsJson;
    }

    public void setEvidenceGapsJson(String evidenceGapsJson) {
        this.evidenceGapsJson = evidenceGapsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        touch();
    }
}
