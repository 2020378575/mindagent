package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.domain.SourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
/**
 * Chroma 向量库网关。
 *
 * <p>当 use-chroma=true 时，把知识库切块镜像到外部向量库，并优先从 Chroma 检索。
 * 项目检索必须带 where 过滤；调用方仍会丢弃 projectId 不符的结果。</p>
 */
public class ChromaGateway {

    private final MindBridgeProperties properties;
    private final WebClient webClient;
    private volatile boolean collectionEnsured;

    public ChromaGateway(MindBridgeProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(properties.getKnowledge().getChromaBaseUrl()).build();
    }

    public void mirror(KnowledgeChunk chunk) {
        if (!properties.getKnowledge().isUseChroma()) {
            return;
        }
        ensureCollection();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", chunk.getSource());
        metadata.put("sourceIndex", chunk.getSourceIndex());
        metadata.put("startOffset", chunk.getStartOffset());
        metadata.put("endOffset", chunk.getEndOffset());
        if (chunk.projectId() != null) {
            metadata.put("projectId", chunk.projectId());
        }
        if (chunk.sourceId() != null) {
            metadata.put("sourceId", chunk.sourceId());
        }
        if (chunk.getSourceType() != null) {
            metadata.put("sourceType", chunk.getSourceType().name());
        }
        if (chunk.getPageNumber() != null) {
            metadata.put("pageNumber", chunk.getPageNumber());
        }
        if (chunk.getHeading() != null) {
            metadata.put("heading", chunk.getHeading());
        }
        Map<String, Object> body = Map.of(
                "ids", List.of(String.valueOf(chunk.getId())),
                "documents", List.of(chunk.getContent()),
                "metadatas", List.of(metadata)
        );
        webClient.post()
                .uri("/api/v1/collections/{collection}/add", properties.getKnowledge().getChromaCollection())
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorComplete()
                .block();
    }

    public List<SearchResult> query(String text, int topK) {
        return query(null, text, topK);
    }

    public List<SearchResult> query(Long projectId, String query, int limit) {
        if (!properties.getKnowledge().isUseChroma()) {
            return List.of();
        }
        ensureCollection();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_texts", List.of(query));
        body.put("n_results", limit);
        body.put("include", List.of("documents", "metadatas", "distances"));
        if (projectId != null) {
            body.put("where", Map.of("projectId", projectId));
        }
        try {
            JsonNode response = webClient.post()
                    .uri("/api/v1/collections/{collection}/query", properties.getKnowledge().getChromaCollection())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return parseResults(response);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public void deleteSource(String source) {
        if (!properties.getKnowledge().isUseChroma()) {
            return;
        }
        ensureCollection();
        Map<String, Object> body = Map.of("where", Map.of("source", source));
        deleteWhere(body);
    }

    public void deleteSource(Long projectId, Long sourceId) {
        if (!properties.getKnowledge().isUseChroma() || sourceId == null) {
            return;
        }
        ensureCollection();
        Map<String, Object> where = new LinkedHashMap<>();
        if (projectId != null) {
            where.put("projectId", projectId);
        }
        where.put("sourceId", sourceId);
        deleteWhere(Map.of("where", where));
    }

    private void deleteWhere(Map<String, Object> body) {
        webClient.post()
                .uri("/api/v1/collections/{collection}/delete", properties.getKnowledge().getChromaCollection())
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorComplete()
                .block();
    }

    private List<SearchResult> parseResults(JsonNode response) {
        if (response == null) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>();
        JsonNode docs = response.path("documents").path(0);
        JsonNode ids = response.path("ids").path(0);
        JsonNode metadatas = response.path("metadatas").path(0);
        JsonNode distances = response.path("distances").path(0);
        for (int i = 0; i < docs.size(); i++) {
            JsonNode metadata = metadatas.path(i);
            results.add(new SearchResult(
                    parseId(ids.path(i).asText()),
                    longOrNull(metadata.path("projectId")),
                    longOrNull(metadata.path("sourceId")),
                    metadata.path("source").asText("chroma"),
                    sourceType(metadata.path("sourceType").asText(null)),
                    intOrNull(metadata.path("pageNumber")),
                    blankToNull(metadata.path("heading").asText(null)),
                    metadata.path("startOffset").asInt(0),
                    metadata.path("endOffset").asInt(0),
                    docs.path(i).asText(),
                    1.0 - distances.path(i).asDouble(1.0)
            ));
        }
        return results;
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long longOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asLong();
    }

    private Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private SourceType sourceType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SourceType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void ensureCollection() {
        if (collectionEnsured) {
            return;
        }
        try {
            webClient.post()
                    .uri("/api/v1/collections")
                    .bodyValue(Map.of("name", properties.getKnowledge().getChromaCollection()))
                    .retrieve()
                    .toBodilessEntity()
                    .onErrorComplete()
                    .block();
        } finally {
            collectionEnsured = true;
        }
    }
}
