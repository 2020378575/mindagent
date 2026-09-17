package com.mindbridge.agent.service.knowledge;

import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.domain.SourceType;

/**
 * RAG 检索结果。项目检索必须带 projectId 和可回溯的来源位置。
 */
public record SearchResult(
        Long chunkId,
        Long projectId,
        Long sourceId,
        String sourceTitle,
        SourceType sourceType,
        Integer pageNumber,
        String heading,
        int startOffset,
        int endOffset,
        String content,
        double score
) {

    public String source() {
        return sourceTitle;
    }

    public SearchResult withScore(double newScore) {
        return new SearchResult(
                chunkId,
                projectId,
                sourceId,
                sourceTitle,
                sourceType,
                pageNumber,
                heading,
                startOffset,
                endOffset,
                content,
                newScore
        );
    }

    public SearchResult withContent(String newContent) {
        return new SearchResult(
                chunkId,
                projectId,
                sourceId,
                sourceTitle,
                sourceType,
                pageNumber,
                heading,
                startOffset,
                endOffset,
                newContent,
                score
        );
    }

    public static SearchResult of(Long chunkId, String source, String content, double score) {
        return new SearchResult(chunkId, null, null, source, null, null, null, 0, 0, content, score);
    }

    public static SearchResult fromChunk(KnowledgeChunk chunk, double score) {
        return new SearchResult(
                chunk.getId(),
                chunk.projectId(),
                chunk.sourceId(),
                chunk.getSource(),
                chunk.getSourceType(),
                chunk.getPageNumber(),
                chunk.getHeading(),
                chunk.getStartOffset(),
                chunk.getEndOffset(),
                chunk.getContent(),
                score
        );
    }
}
