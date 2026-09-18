package com.mindbridge.agent.dto;

import com.mindbridge.agent.domain.ResearchSource;
import com.mindbridge.agent.domain.SourceStatus;
import com.mindbridge.agent.domain.SourceType;
import java.time.Instant;

public record ResearchSourceResponse(
        Long id,
        String filename,
        String contentType,
        SourceType sourceType,
        SourceStatus status,
        long sizeBytes,
        int sectionCount,
        int chunkCount,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static ResearchSourceResponse from(ResearchSource source) {
        return new ResearchSourceResponse(
                source.getId(),
                source.getFilename(),
                source.getContentType(),
                source.getSourceType(),
                source.getStatus(),
                source.getSizeBytes(),
                source.getSectionCount(),
                source.getChunkCount(),
                source.getFailureMessage(),
                source.getCreatedAt(),
                source.getUpdatedAt()
        );
    }
}
