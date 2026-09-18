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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
        name = "research_tasks",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_research_tasks_owner_project_idempotency",
                        columnNames = {"owner_id", "project_id", "idempotency_key"}
                )
        },
        indexes = {
                @Index(name = "idx_research_tasks_project_status", columnList = "project_id, status"),
                @Index(name = "idx_research_tasks_public_id", columnList = "public_id")
        }
)
/**
 * 可恢复的异步研究任务。SSE 只订阅事件，真正执行状态都落在这张表上。
 */
public class ResearchTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String publicId;

    @Column(nullable = false, length = 120)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ResearchProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ResearchTaskType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ResearchTaskStatus status = ResearchTaskStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ResearchTaskStage currentStage;

    @Column(nullable = false)
    private int progressPercent;

    @Column(length = 4000)
    private String question;

    private Long sourceId;

    private Long decisionId;

    private Long experimentId;

    private Long resultReferenceId;

    @Column(length = 80)
    private String errorCode;

    @Column(length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Instant startedAt;

    private Instant completedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
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

    public Long ownerId() {
        return owner == null ? null : owner.getId();
    }

    public ResearchTaskType getType() {
        return type;
    }

    public void setType(ResearchTaskType type) {
        this.type = type;
    }

    public ResearchTaskStatus getStatus() {
        return status;
    }

    public void setStatus(ResearchTaskStatus status) {
        this.status = status;
    }

    public ResearchTaskStage getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(ResearchTaskStage currentStage) {
        this.currentStage = currentStage;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Long getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(Long decisionId) {
        this.decisionId = decisionId;
    }

    public Long getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(Long experimentId) {
        this.experimentId = experimentId;
    }

    public Long getResultReferenceId() {
        return resultReferenceId;
    }

    public void setResultReferenceId(Long resultReferenceId) {
        this.resultReferenceId = resultReferenceId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
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
