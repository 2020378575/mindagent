package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.ResearchTask;
import com.mindbridge.agent.domain.ResearchTaskCheckpoint;
import com.mindbridge.agent.service.task.ResearchTaskService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
/**
 * 工作记忆：权威来源是任务 Checkpoint，Redis 缓存最新投影。
 */
public class ResearchWorkingMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ResearchWorkingMemoryService.class);
    static final String KEY_PREFIX = "evidencelab:working:";

    private final ResearchTaskService researchTaskService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MindBridgeProperties properties;

    public ResearchWorkingMemoryService(
            ResearchTaskService researchTaskService,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MindBridgeProperties properties
    ) {
        this.researchTaskService = researchTaskService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ResearchWorkingMemory load(Long userId, Long projectId, Long taskId) {
        if (taskId == null) {
            return empty(null, projectId);
        }
        ResearchWorkingMemory cached = readCache(taskId);
        if (cached != null && projectId.equals(cached.projectId())) {
            return cached;
        }
        ResearchTask task = researchTaskService.getRequired(taskId);
        if (!projectId.equals(task.projectId())) {
            return empty(taskId, projectId);
        }
        if (userId != null && task.ownerId() != null && !userId.equals(task.ownerId())) {
            return empty(taskId, projectId);
        }
        ResearchWorkingMemory projected = projectFromTask(task);
        writeCache(projected);
        return projected;
    }

    public void refresh(Long taskId) {
        if (taskId == null) {
            return;
        }
        ResearchTask task = researchTaskService.getRequired(taskId);
        writeCache(projectFromTask(task));
    }

    private ResearchWorkingMemory projectFromTask(ResearchTask task) {
        Optional<ResearchTaskCheckpoint> latest = researchTaskService.latestCheckpoint(task.getId());
        String stage = task.getCurrentStage() == null
                ? latest.map(checkpoint -> checkpoint.getStage().name()).orElse(null)
                : task.getCurrentStage().name();
        List<Long> chunkIds = new ArrayList<>();
        String criticSummary = null;
        String draftResult = null;
        if (latest.isPresent()) {
            JsonNode payload = readJson(latest.get().getResultJson());
            chunkIds.addAll(readLongList(payload.path("retrievedChunkIds")));
            if (chunkIds.isEmpty()) {
                chunkIds.addAll(readLongList(payload.path("evidenceChunkIds")));
            }
            criticSummary = textOrNull(payload.path("criticSummary"));
            draftResult = textOrNull(payload.path("draftResult"));
            if (draftResult == null) {
                draftResult = textOrNull(payload.path("observation"));
            }
            if (draftResult == null) {
                draftResult = latest.get().getObservation();
            }
        }
        return new ResearchWorkingMemory(
                task.getId(),
                task.projectId(),
                stage,
                task.getQuestion(),
                List.copyOf(chunkIds),
                criticSummary,
                draftResult
        );
    }

    private ResearchWorkingMemory empty(Long taskId, Long projectId) {
        return new ResearchWorkingMemory(taskId, projectId, null, null, List.of(), null, null);
    }

    private void writeCache(ResearchWorkingMemory memory) {
        if (memory.taskId() == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + memory.taskId(),
                    objectMapper.writeValueAsString(memory),
                    ttl());
        } catch (Exception exception) {
            log.debug("Working memory cache write skipped: {}", exception.getMessage());
        }
    }

    private ResearchWorkingMemory readCache(Long taskId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + taskId);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, ResearchWorkingMemory.class);
        } catch (Exception exception) {
            log.debug("Working memory cache read skipped: {}", exception.getMessage());
            return null;
        }
    }

    private Duration ttl() {
        return Duration.ofHours(Math.max(1, properties.getChat().getShortMemoryTtlHours()));
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.nullNode();
        }
    }

    private List<Long> readLongList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.canConvertToLong()) {
                values.add(item.asLong());
            }
        }
        return values;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }
}
