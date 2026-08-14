package com.modelrag.common.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.dto.SseEvent;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed, bounded and expiring SSE replay buffer. */
@Service
@Profile("!test")
public class RedisSseReplayStore implements SseReplayStore {
    private static final int MAX_EVENTS = 50;
    private static final Duration TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisSseReplayStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void append(String streamKey, SseEvent event) {
        String key = key(streamKey);
        try {
            redis.opsForList().rightPush(key, json.writeValueAsString(event));
            redis.opsForList().trim(key, -MAX_EVENTS, -1);
            redis.expire(key, TTL);
        } catch (Exception error) {
            throw new IllegalStateException("SSE 回放写入 Redis 失败", error);
        }
    }

    @Override
    public List<SseEvent> replay(String streamKey) {
        try {
            List<String> values = redis.opsForList().range(key(streamKey), 0, MAX_EVENTS - 1);
            if (values == null || values.isEmpty()) return List.of();
            return values.stream().map(this::read).toList();
        } catch (RuntimeException error) {
            throw new IllegalStateException("SSE 回放读取 Redis 失败", error);
        }
    }

    private SseEvent read(String value) {
        try {
            return json.readValue(value, SseEvent.class);
        } catch (Exception error) {
            throw new IllegalStateException("SSE 回放事件无法解析", error);
        }
    }

    private String key(String streamKey) {
        return "modelrag:sse:replay:" + streamKey;
    }
}
