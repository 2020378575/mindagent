package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.ResearchTaskType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateResearchTaskRequest(
        @NotBlank @Size(max = 120) String idempotencyKey,
        @NotNull ResearchTaskType type,
        @Size(max = 4000) String question,
        Long sourceId,
        Long decisionId,
        Long experimentId
) {
}
