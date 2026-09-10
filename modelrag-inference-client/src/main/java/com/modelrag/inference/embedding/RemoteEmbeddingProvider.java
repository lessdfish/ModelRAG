package com.modelrag.inference.embedding;

import com.modelrag.inference.client.AiServiceClient;
import com.modelrag.inference.client.AiServiceException;
import com.modelrag.inference.client.AiServiceProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Stateless remote embedding adapter. Java application policy stays outside this class. */
@Service
public class RemoteEmbeddingProvider implements EmbeddingComputeProvider {
    public static final String PROFILE = "Qwen3-Embedding-0.6B";
    public static final int DIMENSIONS = 1024;
    private static final int MAX_BATCH = 32;
    private static final int MAX_TEXT_CHARS = 100_000;

    private final AiServiceClient client;
    private final AiServiceProperties properties;

    public RemoteEmbeddingProvider(AiServiceClient client, AiServiceProperties properties) {
        this.client = client;
        this.properties = properties == null ? new AiServiceProperties() : properties;
    }

    @Override
    public List<float[]> embed(String profile, int dimensions, List<String> texts, Duration timeout) {
        validateRequest(profile, dimensions, texts);
        Duration budget = timeout == null ? Duration.ofMillis(properties.requestTimeoutMs()) : timeout;
        long deadline = deadline(budget);
        int attempts = 0;
        while (true) {
            try {
                EmbeddingResponse response = client.postJson("embeddings", "/v1/embeddings",
                        new EmbeddingRequest(profile, dimensions, texts), EmbeddingResponse.class,
                        remaining(deadline));
                return validateResponse(profile, dimensions, texts.size(), response);
            } catch (AiServiceException error) {
                if (attempts++ == 0 && error.retryable() && remainingMillis(deadline) > 1) continue;
                throw error;
            }
        }
    }

    private void validateRequest(String profile, int dimensions, List<String> texts) {
        if (!PROFILE.equals(profile) || dimensions != DIMENSIONS) {
            throw new IllegalArgumentException("仅支持 Qwen3-Embedding-0.6B/1024");
        }
        if (texts == null || texts.isEmpty() || texts.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Embedding 批次必须为 1-32 条");
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding 文本不能为空");
            if (text.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("Embedding 文本超过大小上限");
        }
    }

    private List<float[]> validateResponse(String profile, int dimensions, int expected, EmbeddingResponse response) {
        if (response == null || !profile.equals(response.model()) || response.dimensions() != dimensions
                || response.embeddings().size() != expected) {
            throw new AiServiceException("Embedding 响应不匹配", 502, false);
        }
        for (float[] vector : response.embeddings()) {
            if (vector == null || vector.length != DIMENSIONS) throw new AiServiceException("Embedding 维度无效", 502, false);
            for (float value : vector) {
                if (!Float.isFinite(value)) throw new AiServiceException("Embedding 数值无效", 502, false);
            }
        }
        return response.embeddings();
    }

    private long deadline(Duration budget) {
        long millis = Math.max(1, Math.min(budget.toMillis(), properties.requestTimeoutMs()));
        long now = System.nanoTime();
        long delta = Math.min(TimeUnit.NANOSECONDS.convert(millis, TimeUnit.MILLISECONDS), Long.MAX_VALUE - now);
        return now + delta;
    }

    private Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }

    private long remainingMillis(long deadline) {
        return Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
    }

}
