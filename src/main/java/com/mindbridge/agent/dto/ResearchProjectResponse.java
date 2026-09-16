package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.ProjectStatus;
import com.mindbridge.agent.domain.ResearchProject;
import java.time.Instant;

public record ResearchProjectResponse(
        Long id,
        String name,
        String objective,
        String constraints,
        ProjectStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ResearchProjectResponse from(ResearchProject project) {
        return new ResearchProjectResponse(
                project.getId(),
                project.getName(),
                project.getObjective(),
                project.getConstraints(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }
}
