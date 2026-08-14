package com.modelrag.agent.tool;

import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Production coordination state; no business or tool fact is kept in the JVM. */
@Service
@Profile("!test")
public class RedisToolCoordinationStore implements ToolCoordinationStore {
    private final StringRedisTemplate redis;

    public RedisToolCoordinationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void ensureAvailable() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            if (!"PONG".equalsIgnoreCase(pong)) throw unavailable();
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    @Override
    public String circuit(String toolName) {
        return redis.opsForValue().get(circuitKey(toolName));
    }

    @Override
    public void saveCircuit(String toolName, String state, int failures, long openedUntil, Duration ttl) {
        redis.opsForValue().set(circuitKey(toolName), state + "|" + failures + "|" + openedUntil, ttl);
    }

    @Override
    public long incrementRate(String toolName, Duration window) {
        String key = "modelrag:tool:rate:" + toolName;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) redis.expire(key, window);
        return count == null ? 0 : count;
    }

    private String circuitKey(String toolName) {
        return "modelrag:tool:circuit:" + toolName;
    }

    private RuntimeException unavailable() {
        return new com.modelrag.common.exception.BusinessException(
                com.modelrag.common.exception.ErrorCode.DEPENDENCY_UNAVAILABLE,
                "工具执行需要 Redis 协调服务");
    }
}
