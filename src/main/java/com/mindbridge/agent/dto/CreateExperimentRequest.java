package com.mindbridge.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateExperimentRequest(
        @NotNull Long decisionId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String hypothesis,
        @Size(max = 8000) String setupNotes
) {
}
