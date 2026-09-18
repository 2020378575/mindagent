package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.domain.ResearchSource;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import com.mindbridge.agent.repository.ResearchSourceRepository;
import com.mindbridge.agent.service.document.ParsedDocument;
import com.mindbridge.agent.service.document.ResearchSourceService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
/**
 * 项目级混合检索。只在当前 projectId 的切块上做向量、BM25、重排和相邻片段扩展。
 */
public class ProjectKnowledgeService {

    static final String SOURCE_NOT_FOUND_MESSAGE = "Research source not found";
    static final String EMPTY_CHUNKS_MESSAGE = "Research source has no usable text chunks";
    private static final double VECTOR_WEIGHT = 0.65;
    private static final double BM25_WEIGHT = 0.35;
    private static final int FAILURE_MESSAGE_LIMIT = 500;

    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final ResearchSourceRepository researchSourceRepository;
    private final ResearchSourceService researchSourceService;
    private final MindBridgeProperties properties;
    private final ChromaGateway chromaGateway;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeReranker knowledgeReranker;
    private final ObjectMapper objectMapper;
    private final KnowledgeChunker chunker = new KnowledgeChunker();
    private final Bm25Scorer bm25Scorer = new Bm25Scorer();

    public ProjectKnowledgeService(
            KnowledgeChunkRepository knowledgeChunkRepository,
            ResearchSourceRepository researchSourceRepository,
            ResearchSourceService researchSourceService,
            MindBridgeProperties properties,
            ChromaGateway chromaGateway,
            EmbeddingClient embeddingClient,
            KnowledgeReranker knowledgeReranker,
            ObjectMapper objectMapper
    ) {
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.researchSourceRepository = researchSourceRepository;
        this.researchSourceService = researchSourceService;
        this.properties = properties;
        this.chromaGateway = chromaGateway;
        this.embeddingClient = embeddingClient;
        this.knowledgeReranker = knowledgeReranker;
        this.objectMapper = objectMapper;
    }

    public int ingest(Long projectId, Long sourceId, ParsedDocument document) {
        ResearchSource source = researchSourceRepository.findByIdAndProject_Id(sourceId, projectId)
                .orElseThrow(() -> new IllegalArgumentException(SOURCE_NOT_FOUND_MESSAGE));
        try {
            List<KnowledgeChunker.PositionedChunk> chunks = chunker.chunk(
                    document,
                    properties.getKnowledge().getChunkSize(),
                    properties.getKnowledge().getChunkOverlap());
            if (chunks.isEmpty()) {
                researchSourceService.markFailed(sourceId, EMPTY_CHUNKS_MESSAGE);
                throw new IllegalArgumentException(EMPTY_CHUNKS_MESSAGE);
            }
            knowledgeChunkRepository.deleteByResearchSource_Id(sourceId);
            chromaGateway.deleteSource(projectId, sourceId);
            int saved = 0;
            for (KnowledgeChunker.PositionedChunk positioned : chunks) {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setProject(source.getProject());
                chunk.setResearchSource(source);
                chunk.setSource(document.title());
                chunk.setSourceType(document.sourceType());
                chunk.setPageNumber(positioned.pageNumber());
                chunk.setHeading(positioned.heading());
                chunk.setStartOffset(positioned.startOffset());
                chunk.setEndOffset(positioned.endOffset());
                chunk.setSourceIndex(positioned.sourceIndex());
                chunk.setContent(positioned.content());
                chunk.setEmbeddingJson(serializeEmbedding(safeEmbedding(positioned.content())));
                KnowledgeChunk persisted = knowledgeChunkRepository.save(chunk);
                chromaGateway.mirror(persisted);
                saved++;
            }
            researchSourceService.markReady(sourceId, document.sections().size(), saved);
            return saved;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            researchSourceService.markFailed(sourceId, safeFailureMessage(exception));
            throw exception;
        }
    }

    public List<SearchResult> retrieve(Long projectId, String query, int topK) {
        if (projectId == null || topK <= 0 || query == null || query.isBlank()) {
            return List.of();
        }
        List<KnowledgeChunk> projectChunks = knowledgeChunkRepository.findByProject_Id(projectId);
        List<SearchResult> vector = vectorCandidates(projectId, query, candidateLimit(topK), projectChunks);
        List<SearchResult> keyword = bm25Scorer.rank(query, projectChunks, candidateLimit(topK)).stream()
                .filter(result -> projectId.equals(result.projectId()))
                .toList();
        return expandBestContext(
                projectId,
                knowledgeReranker.rerank(query, merge(projectId, vector, keyword), topK),
                topK
        );
    }

    private List<SearchResult> vectorCandidates(
            Long projectId,
            String query,
            int limit,
            List<KnowledgeChunk> projectChunks
    ) {
        List<SearchResult> chromaResults = chromaGateway.query(projectId, query, limit).stream()
                .filter(result -> projectId.equals(result.projectId()))
                .toList();
        if (!chromaResults.isEmpty()) {
            return chromaResults;
        }
        List<Double> queryEmbedding = safeEmbedding(query);
        if (queryEmbedding.isEmpty()) {
            return List.of();
        }
        return projectChunks.stream()
                .map(chunk -> SearchResult.fromChunk(
                        chunk,
                        cosine(queryEmbedding, parseEmbedding(chunk.getEmbeddingJson()))))
                .filter(result -> result.score() > 0.0)
                .filter(result -> projectId.equals(result.projectId()))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(limit)
                .toList();
    }

    private List<SearchResult> merge(Long projectId, List<SearchResult> vectorResults, List<SearchResult> bm25Results) {
        Map<String, HybridCandidate> candidates = new LinkedHashMap<>();
        double maxVectorScore = maxScore(vectorResults);
        double maxBm25Score = maxScore(bm25Results);
        mergeRoute(candidates, vectorResults, maxVectorScore, true);
        mergeRoute(candidates, bm25Results, maxBm25Score, false);
        int limit = Math.max(1, Math.max(vectorResults.size(), bm25Results.size()));
        return candidates.values().stream()
                .map(HybridCandidate::toSearchResult)
                .filter(result -> projectId.equals(result.projectId()))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(limit)
                .toList();
    }

    private void mergeRoute(
            Map<String, HybridCandidate> candidates,
            List<SearchResult> results,
            double maxScore,
            boolean vectorRoute
    ) {
        if (results.isEmpty() || maxScore <= 0.0) {
            return;
        }
        for (int rank = 0; rank < results.size(); rank++) {
            SearchResult result = results.get(rank);
            double normalizedScore = Math.max(0.0, result.score()) / maxScore;
            double rankBoost = 1.0 / (rank + 1.0);
            double routeScore = normalizedScore * 0.85 + rankBoost * 0.15;
            HybridCandidate candidate = candidates.computeIfAbsent(candidateKey(result), key -> new HybridCandidate(result));
            if (vectorRoute) {
                candidate.vectorScore = Math.max(candidate.vectorScore, routeScore);
            } else {
                candidate.bm25Score = Math.max(candidate.bm25Score, routeScore);
            }
        }
    }

    private double maxScore(List<SearchResult> results) {
        return results.stream()
                .mapToDouble(SearchResult::score)
                .filter(score -> score > 0.0)
                .max()
                .orElse(0.0);
    }

    private String candidateKey(SearchResult result) {
        if (result.chunkId() != null) {
            return "id:" + result.chunkId();
        }
        return "content:" + result.source() + ":" + result.content();
    }

    private List<SearchResult> expandBestContext(Long projectId, List<SearchResult> ranked, int topK) {
        List<SearchResult> owned = ranked.stream()
                .filter(result -> projectId.equals(result.projectId()))
                .toList();
        if (owned.isEmpty()) {
            return List.of();
        }
        SearchResult best = owned.get(0);
        SearchResult expanded = expand(projectId, best);
        List<SearchResult> results = new ArrayList<>();
        results.add(expanded);
        owned.stream()
                .skip(1)
                .filter(result -> !Objects.equals(result.chunkId(), expanded.chunkId()))
                .limit(Math.max(0, topK - 1))
                .forEach(results::add);
        return results;
    }

    private SearchResult expand(Long projectId, SearchResult result) {
        if (result.chunkId() == null || result.sourceId() == null) {
            return result;
        }
        return knowledgeChunkRepository.findById(result.chunkId())
                .filter(chunk -> projectId.equals(chunk.projectId()))
                .map(chunk -> {
                    List<KnowledgeChunk> neighbors = knowledgeChunkRepository
                            .findByResearchSource_IdAndSourceIndexBetweenOrderBySourceIndexAsc(
                                    chunk.sourceId(),
                                    Math.max(0, chunk.getSourceIndex() - 1),
                                    chunk.getSourceIndex() + 1)
                            .stream()
                            .filter(neighbor -> projectId.equals(neighbor.projectId()))
                            .toList();
                    if (neighbors.isEmpty()) {
                        return SearchResult.fromChunk(chunk, result.score());
                    }
                    String expandedContent = String.join("\n\n", neighbors.stream()
                            .map(KnowledgeChunk::getContent)
                            .toList());
                    return SearchResult.fromChunk(chunk, result.score()).withContent(expandedContent);
                })
                .orElse(result);
    }

    private int candidateLimit(int topK) {
        return Math.max(Math.max(topK * 4, 20), properties.getKnowledge().getRerankerCandidateLimit());
    }

    private List<Double> safeEmbedding(String text) {
        try {
            return embeddingClient.embed(text);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String serializeEmbedding(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Double> parseEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(embeddingJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private String safeFailureMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        if (message.length() > FAILURE_MESSAGE_LIMIT) {
            return message.substring(0, FAILURE_MESSAGE_LIMIT);
        }
        return message;
    }

    private static class HybridCandidate {
        private final SearchResult result;
        private double vectorScore;
        private double bm25Score;

        private HybridCandidate(SearchResult result) {
            this.result = result;
        }

        private SearchResult toSearchResult() {
            return result.withScore(vectorScore * VECTOR_WEIGHT + bm25Score * BM25_WEIGHT);
        }
    }
}
