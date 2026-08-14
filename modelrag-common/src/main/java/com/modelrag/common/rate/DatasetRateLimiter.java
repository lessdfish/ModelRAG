package com.modelrag.common.rate;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis-backed fixed one-minute request window shared by application instances. */
@Service
@Profile("!test")
public class DatasetRateLimiter {
    private static final int QA_LIMIT = 100;
    private static final int UPLOAD_LIMIT = 20;
    private static final int MODEL_LIMIT = 30;
    private final StringRedisTemplate redis;
    private final boolean failOpen;

    public DatasetRateLimiter(StringRedisTemplate redis,
            @Value("${modelrag.rate-limit.redis-fail-open:true}") boolean failOpen) {
        this.redis = redis;
        this.failOpen = failOpen;
    }

    public void check(long datasetId) {
        check("qa:dataset:" + datasetId, QA_LIMIT, "知识库请求过于频繁，请稍后再试");
    }

    public void checkUpload(String userId) {
        check("upload:user:" + safe(userId), UPLOAD_LIMIT, "文档上传过于频繁，请稍后再试");
    }

    public void checkModel(String userId) {
        check("model:user:" + safe(userId), MODEL_LIMIT, "模型调用过于频繁，请稍后再试");
    }

    private void check(String scope, int limit, String message) {
        String key = "modelrag:rate:" + scope + ":" + (System.currentTimeMillis() / 60_000L);
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, Duration.ofMinutes(2));
            if (count != null && count > limit) throw new BusinessException(ErrorCode.RATE_LIMITED, message);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            if (!failOpen) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "限流服务暂时不可用");
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "anonymous" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
