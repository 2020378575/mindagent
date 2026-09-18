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
        name = "research_memory_items",
        indexes = {
                @Index(name = "idx_research_memory_project_active", columnList = "project_id, active, updated_at"),
                @Index(name = "idx_research_memory_source", columnList = "source_type, source_record_id")
        }
)
/**
 * 项目级长期研究记忆。草稿决策不能写入；被证伪后仍可审计但不参与主动召回。
 */
public class ResearchMemoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ResearchProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ResearchMemoryType type = ResearchMemoryType.FINDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ResearchMemorySourceType sourceType;

    @Column(nullable = false)
    private Long sourceRecordId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private MemoryValidationStatus validationStatus;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String summary;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String evidenceChunkIdsJson;

    @Column(nullable = false)
    private boolean active = true;

    private Long reviewId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

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

    public ResearchMemoryType getType() {
        return type;
    }

    public void setType(ResearchMemoryType type) {
        this.type = type;
    }

    public ResearchMemorySourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(ResearchMemorySourceType sourceType) {
        this.sourceType = sourceType;
    }

    public Long getSourceRecordId() {
        return sourceRecordId;
    }

    public void setSourceRecordId(Long sourceRecordId) {
        this.sourceRecordId = sourceRecordId;
    }

    public MemoryValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(MemoryValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getEvidenceChunkIdsJson() {
        return evidenceChunkIdsJson;
    }

    public void setEvidenceChunkIdsJson(String evidenceChunkIdsJson) {
        this.evidenceChunkIdsJson = evidenceChunkIdsJson;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        touch();
    }
}
