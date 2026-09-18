package com.mindbridge.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateResearchProjectRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 4000) String objective,
        @Size(max = 4000) String constraints
) {
}
