package com.mindbridge.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompleteExperimentRequest(
        @NotBlank @Size(max = 8000) String resultSummary,
        @Size(max = 8000) String metricsJson
) {
}
