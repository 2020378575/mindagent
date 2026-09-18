package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.MemoryValidationStatus;
import com.mindbridge.agent.domain.ResearchMemoryItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
/**
 * 项目级长期研究记忆的 Chroma 镜像。查询必须带 projectId，并丢弃非活跃或不匹配项目。
 */
public class ResearchMemoryChromaGateway {

    private final MindBridgeProperties properties;
    private final WebClient webClient;
    private volatile boolean collectionEnsured;

    public ResearchMemoryChromaGateway(MindBridgeProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.baseUrl(properties.getMemory().getChromaBaseUrl()).build();
    }

    public void mirror(ResearchMemoryItem item) {
        if (!properties.getMemory().isUseChroma() || item.getId() == null || !item.isActive()) {
            return;
        }
        ensureCollection();
        delete(item.getId());
        Map<String, Object> body = Map.of(
                "ids", List.of(chromaId(item.getId())),
                "documents", List.of(item.getSummary()),
                "metadatas", List.of(metadata(item))
        );
        webClient.post()
                .uri("/api/v1/collections/{collection}/add", collectionName())
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .onErrorComplete()
                .block();
    }

    public List<ResearchMemoryMatch> query(Long projectId, String text, int topK) {
        if (!properties.getMemory().isUseChroma()
                || projectId == null
                || text == null
                || text.isBlank()) {
            return List.of();
        }
        ensureCollection();
        Map<String, Object> body = Map.of(
                "query_texts", List.of(text),
                "n_results", Math.max(1, topK),
                "where", Map.of(
                        "projectId", String.valueOf(projectId),
                        "active", "true"
                ),
                "include", List.of("documents", "metadatas", "distances")
        );
        try {
            JsonNode response = webClient.post()
                    .uri("/api/v1/collections/{collection}/query", collectionName())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            return parseMatches(projectId, response);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public void delete(Long memoryId) {
        if (!properties.getMemory().isUseChroma() || memoryId == null) {
            return;
        }
        ensureCollection();
        webClient.post()
                .uri("/api/v1/collections/{collection}/delete", collectionName())
                .bodyValue(Map.of("ids", List.of(chromaId(memoryId))))
                .retrieve()
                .toBodilessEntity()
                .onErrorComplete()
                .block();
    }

    private List<ResearchMemoryMatch> parseMatches(Long projectId, JsonNode response) {
        if (response == null) {
            return List.of();
        }
        List<ResearchMemoryMatch> matches = new ArrayList<>();
        JsonNode documents = response.path("documents").path(0);
        JsonNode metadatas = response.path("metadatas").path(0);
        JsonNode distances = response.path("distances").path(0);
        for (int i = 0; i < metadatas.size(); i++) {
            JsonNode metadata = metadatas.path(i);
            Long memoryProjectId = parseLong(metadata.path("projectId").asText());
            if (!projectId.equals(memoryProjectId)) {
                continue;
            }
            if (!"true".equalsIgnoreCase(metadata.path("active").asText("false"))) {
                continue;
            }
            Long memoryId = parseLong(metadata.path("memoryId").asText());
            if (memoryId == null) {
                continue;
            }
            MemoryValidationStatus status = parseStatus(metadata.path("validationStatus").asText(null));
            if (status == MemoryValidationStatus.REFUTED) {
                continue;
            }
            String summary = documents.path(i).asText(metadata.path("summary").asText(""));
            double score = 1.0 - distances.path(i).asDouble(1.0);
            matches.add(new ResearchMemoryMatch(memoryId, projectId, summary, status, score));
        }
        return matches;
    }

    private Map<String, Object> metadata(ResearchMemoryItem item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memoryId", String.valueOf(item.getId()));
        metadata.put("projectId", String.valueOf(item.projectId()));
        metadata.put("ownerId", String.valueOf(item.getOwner().getId()));
        metadata.put("validationStatus", item.getValidationStatus().name());
        metadata.put("active", String.valueOf(item.isActive()));
        metadata.put("sourceType", item.getSourceType().name());
        return metadata;
    }

    private String collectionName() {
        String configured = properties.getMemory().getChromaCollection();
        if (configured == null || configured.isBlank() || configured.contains("user_memory")) {
            return "evidencelab_research_memory";
        }
        return configured;
    }

    private String chromaId(Long memoryId) {
        return "research-memory:" + memoryId;
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private MemoryValidationStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return MemoryValidationStatus.CONFIRMED;
        }
        try {
            return MemoryValidationStatus.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return MemoryValidationStatus.CONFIRMED;
        }
    }

    private void ensureCollection() {
        if (collectionEnsured) {
            return;
        }
        try {
            webClient.post()
                    .uri("/api/v1/collections")
                    .bodyValue(Map.of("name", collectionName()))
                    .retrieve()
                    .toBodilessEntity()
                    .onErrorComplete()
                    .block();
        } finally {
            collectionEnsured = true;
        }
    }
}
