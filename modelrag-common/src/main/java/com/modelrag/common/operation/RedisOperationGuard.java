package com.modelrag.common.operation;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Redis is a coordination dependency for side effects, not a source of business truth. */
@Service
@Profile("!test")
public class RedisOperationGuard implements OperationGuard {
    private final StringRedisTemplate redis;

    public RedisOperationGuard(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void requireAvailableForSideEffect() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            if (!"PONG".equalsIgnoreCase(pong)) throw unavailable();
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "副作用操作需要 Redis 协调服务");
        }
    }

    @Override
    public void claimSideEffect(String idempotencyKey) {
        requireAvailableForSideEffect();
        String key = "modelrag:side-effect:" + digest(idempotencyKey);
        try {
            Boolean claimed = redis.opsForValue().setIfAbsent(key, "claimed", java.time.Duration.ofHours(24));
            if (Boolean.FALSE.equals(claimed)) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同副作用请求已执行或正在执行");
            }
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "副作用操作需要 Redis 幂等协调");
        }
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("副作用幂等键生成失败", error);
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "副作用操作需要 Redis 协调服务");
    }
}
