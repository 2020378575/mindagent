package com.mindbridge.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

@Service
/**
 * 项目短期记忆：Redis 有序集合保存最近资料/实验/决策事件，最多 12 条。
 */
public class ProjectRecentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ProjectRecentMemoryService.class);
    static final String KEY_PREFIX = "evidencelab:recent:";
    static final int MAX_EVENTS = 12;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MindBridgeProperties properties;

    public ProjectRecentMemoryService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            MindBridgeProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void rememberProjectEvent(Long projectId, ResearchProjectEvent event) {
        if (projectId == null || event == null) {
            return;
        }
        try {
            String key = KEY_PREFIX + projectId;
            String payload = objectMapper.writeValueAsString(event);
            double score = event.at().toEpochMilli();
            redisTemplate.opsForZSet().add(key, payload, score);
            Long size = redisTemplate.opsForZSet().zCard(key);
            if (size != null && size > MAX_EVENTS) {
                redisTemplate.opsForZSet().removeRange(key, 0, size - MAX_EVENTS - 1);
            }
            redisTemplate.expire(key, ttl());
        } catch (Exception exception) {
            log.debug("Recent project memory write skipped: {}", exception.getMessage());
        }
    }

    public List<String> recentEvents(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                    .reverseRangeWithScores(KEY_PREFIX + projectId, 0, MAX_EVENTS - 1);
            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }
            return tuples.stream()
                    .map(ZSetOperations.TypedTuple::getValue)
                    .map(this::formatEvent)
                    .toList();
        } catch (Exception exception) {
            log.debug("Recent project memory read skipped: {}", exception.getMessage());
            return List.of();
        }
    }

    private String formatEvent(String raw) {
        try {
            ResearchProjectEvent event = objectMapper.readValue(raw, ResearchProjectEvent.class);
            return "%s: %s".formatted(event.kind(), event.summary());
        } catch (Exception ignored) {
            return raw;
        }
    }

    private Duration ttl() {
        return Duration.ofHours(Math.max(24, properties.getChat().getShortMemoryTtlHours() * 7));
    }
}
