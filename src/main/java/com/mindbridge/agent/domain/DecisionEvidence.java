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

@Entity
@Table(
        name = "decision_evidence",
        indexes = {
                @Index(name = "idx_decision_evidence_decision", columnList = "decision_id"),
                @Index(name = "idx_decision_evidence_chunk", columnList = "chunk_id")
        }
)
/**
 * 决策引用的证据片段。同一 chunk 不能同时作为 support 与 oppose。
 */
public class DecisionEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id", nullable = false)
    private DecisionRecord decision;

    @Column(nullable = false)
    private Long chunkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvidenceStance stance;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String note;

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

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public EvidenceStance getStance() {
        return stance;
    }

    public void setStance(EvidenceStance stance) {
        this.stance = stance;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
