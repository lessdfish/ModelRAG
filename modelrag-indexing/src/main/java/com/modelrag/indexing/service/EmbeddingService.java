package com.modelrag.indexing.service;

import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.common.cache.EmbeddingCache;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelHealthRegistry;
import com.modelrag.inference.embedding.EmbeddingComputeProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Production embedding boundary. Qwen3-Embedding-0.6B is the only accepted profile. */
@Service
@Profile("!test")
public class EmbeddingService implements TextEmbeddingProvider {
    private static final int DIMENSIONS = 1024;
    private static final String REQUIRED_MODEL = "Qwen3-Embedding-0.6B";
    private final EmbeddingCache cache;
    private final String model;
    private final TokenUsageTracker tokens;
    private final ObjectProvider<ModelHealthRegistry> health;
    private final EmbeddingComputeProvider computeProvider;
    private final Duration timeout;

    @Autowired
    public EmbeddingService(EmbeddingCache cache,
            EmbeddingComputeProvider computeProvider,
            @Value("${modelrag.inference.embedding-profile:Qwen3-Embedding-0.6B}") String model,
            @Value("${modelrag.inference.request-timeout-ms:30000}") long timeoutMs,
            TokenUsageTracker tokens, ObjectProvider<ModelHealthRegistry> health) {
        if (!REQUIRED_MODEL.equals(model)) {
            throw new IllegalStateException("生产 Embedding 只允许 " + REQUIRED_MODEL);
        }
        this.cache = cache;
        this.model = model;
        this.tokens = tokens;
        this.health = health;
        this.computeProvider = java.util.Objects.requireNonNull(computeProvider, "Embedding 计算提供方不能为空");
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(600_000, timeoutMs)));
    }

    /** Source-compatible constructor for test doubles and legacy direct callers. */
    public EmbeddingService(EmbeddingCache cache, boolean ignoredEnabled, String ignoredUrl, String model,
            TokenUsageTracker tokens, ObjectProvider<ModelHealthRegistry> health) {
        this(cache, new DisabledEmbeddingProvider(), model, 30_000, tokens, health);
    }

    public float[] embed(String text) { return embed(0, text); }

    @Override
    public float[] embed(long datasetId, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding 文本不能为空");
        float[] value = cache.get(cacheKey(text), ignored -> {
            if (datasetId > 0) tokens.recordEmbedding(datasetId, text);
            return compute(text);
        });
        validateVector(value);
        return value;
    }

    /** Provider requests are capped at 32 inputs while preserving one cache entry per text. */
    @Override
    public List<float[]> embedBatch(long datasetId, List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<String> normalized = texts.stream().map(value -> value == null ? "" : value).toList();
        if (normalized.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Embedding 文本不能为空");
        List<float[]> result = new ArrayList<>(normalized.size());
        Map<String, List<Integer>> missing = new LinkedHashMap<>();
        for (int index = 0; index < normalized.size(); index++) {
            String value = normalized.get(index);
            float[] cached = cache.getIfPresent(cacheKey(value));
            result.add(cached);
            if (cached == null) missing.computeIfAbsent(value, ignored -> new ArrayList<>()).add(index);
        }
        List<String> inputs = List.copyOf(missing.keySet());
        for (int start = 0; start < inputs.size(); start += 32) {
            List<String> batch = inputs.subList(start, Math.min(inputs.size(), start + 32));
            List<float[]> computed = computeBatch(batch);
            for (int index = 0; index < batch.size(); index++) {
                String value = batch.get(index);
                float[] vector = computed.get(index);
                cache.put(cacheKey(value), vector);
                if (datasetId > 0) tokens.recordEmbedding(datasetId, value);
                for (Integer position : missing.get(value)) result.set(position, vector);
            }
        }
        result.forEach(this::validateVector);
        return List.copyOf(result);
    }

    protected float[] compute(String text) {
        return computeBatch(List.of(text)).get(0);
    }

    private List<float[]> computeBatch(List<String> texts) {
        String modelName = "remote-embedding-" + model;
        ModelHealthRegistry registry = health.getIfAvailable();
        if (registry != null && !registry.available("EMBEDDING", modelName)) {
            throw new IllegalStateException("Embedding 模型暂时熔断: " + model);
        }
        try {
            List<float[]> values = computeProvider.embed(model, DIMENSIONS, texts, timeout);
            validateBatch(values, texts.size());
            if (registry != null) registry.success("EMBEDDING", modelName);
            return values;
        } catch (RuntimeException error) {
            if (registry != null) registry.failure("EMBEDDING", modelName);
            if (error instanceof IllegalStateException state && state.getMessage() != null
                    && state.getMessage().startsWith("Embedding")) throw state;
            throw new IllegalStateException("Embedding 计算调用失败", error);
        }
    }

    private void validateBatch(List<float[]> values, int expected) {
        if (values == null || values.size() != expected) throw new IllegalStateException("Embedding 返回数量不匹配");
        values.forEach(this::validateVector);
    }

    private void validateVector(float[] value) {
        if (value == null || value.length != DIMENSIONS) throw new IllegalStateException("Embedding 期望 1024 维");
        for (float component : value) {
            if (!Float.isFinite(component)) throw new IllegalStateException("Embedding 向量包含无效数值");
        }
    }

    private String cacheKey(String text) { return model + ":" + DIMENSIONS + "\n" + text; }

    private static final class DisabledEmbeddingProvider implements EmbeddingComputeProvider {
        @Override public List<float[]> embed(String profile, int dimensions, List<String> texts, Duration timeout) {
            throw new IllegalStateException("Embedding 计算服务未启用");
        }
    }
}
