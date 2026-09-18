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
        name = "knowledge_chunks",
        indexes = {
                @Index(name = "idx_knowledge_chunks_project", columnList = "project_id"),
                @Index(name = "idx_knowledge_chunks_source", columnList = "research_source_id, source_index")
        }
)
/**
 * 知识库切块。
 *
 * <p>项目资料带页码/标题/偏移；管理员全局知识库仍可只填 source 文本来源。</p>
 */
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ResearchProject project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "research_source_id")
    private ResearchSource researchSource;

    @Column(nullable = false, length = 180)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SourceType sourceType;

    @Column
    private Integer pageNumber;

    @Column(length = 500)
    private String heading;

    @Column(nullable = false)
    private int startOffset;

    @Column(nullable = false)
    private int endOffset;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int sourceIndex;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String embeddingJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

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

    public ResearchSource getResearchSource() {
        return researchSource;
    }

    public void setResearchSource(ResearchSource researchSource) {
        this.researchSource = researchSource;
    }

    public Long sourceId() {
        return researchSource == null ? null : researchSource.getId();
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(int startOffset) {
        this.startOffset = startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public void setEndOffset(int endOffset) {
        this.endOffset = endOffset;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public void setSourceIndex(int sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getEmbeddingJson() {
        return embeddingJson;
    }

    public void setEmbeddingJson(String embeddingJson) {
        this.embeddingJson = embeddingJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
