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
        name = "research_task_checkpoints",
        indexes = {
                @Index(name = "idx_research_task_checkpoints_task_step", columnList = "task_id, step_number")
        }
)
/**
 * 任务阶段检查点。重启后从最近一次成功 checkpoint 继续，而不是从头跑。
 */
public class ResearchTaskCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private ResearchTask task;

    @Column(nullable = false)
    private int stepNumber;

    @Column(nullable = false, length = 80)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ResearchTaskStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ResearchTaskStatus status = ResearchTaskStatus.SUCCEEDED;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(length = 500)
    private String observation;

    @Column(nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(nullable = false)
    private Instant completedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ResearchTask getTask() {
        return task;
    }

    public void setTask(ResearchTask task) {
        this.task = task;
    }

    public int getStepNumber() {
        return stepNumber;
    }

    public void setStepNumber(int stepNumber) {
        this.stepNumber = stepNumber;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public ResearchTaskStage getStage() {
        return stage;
    }

    public void setStage(ResearchTaskStage stage) {
        this.stage = stage;
    }

    public ResearchTaskStatus getStatus() {
        return status;
    }

    public void setStatus(ResearchTaskStatus status) {
        this.status = status;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getObservation() {
        return observation;
    }

    public void setObservation(String observation) {
        this.observation = observation;
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
}
