package com.modelrag.indexing.service;

import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.common.cache.EmbeddingCache;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelHealthRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Production embedding boundary. Qwen3-Embedding-0.6B is the only accepted profile. */
@Service
@Profile("!test")
public class EmbeddingService implements TextEmbeddingProvider {
    private static final int DIMENSIONS = 1024;
    private static final String REQUIRED_MODEL = "Qwen3-Embedding-0.6B";
    private static final String OLLAMA_MODEL = "qwen3-embedding:0.6b";
    private final EmbeddingCache cache;
    private final boolean enabled;
    private final String model;
    private final TokenUsageTracker tokens;
    private final ObjectProvider<ModelHealthRegistry> health;
    private final EmbeddingModel embedding;

    public EmbeddingService(EmbeddingCache cache,
            @Value("${modelrag.ollama.enabled:false}") boolean enabled,
            @Value("${modelrag.ollama.url:http://127.0.0.1:11434}") String url,
            @Value("${modelrag.ollama.embedding-model:Qwen3-Embedding-0.6B}") String model,
            TokenUsageTracker tokens, ObjectProvider<ModelHealthRegistry> health) {
        if (!REQUIRED_MODEL.equals(model)) {
            throw new IllegalStateException("生产 Embedding 只允许 " + REQUIRED_MODEL);
        }
        this.cache = cache;
        this.enabled = enabled;
        this.model = model;
        this.tokens = tokens;
        this.health = health;
        this.embedding = OllamaEmbeddingModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(normalizeUrl(url)).build())
                .options(OllamaEmbeddingOptions.builder().model(OLLAMA_MODEL).dimensions(DIMENSIONS).build())
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
    }

    public float[] embed(String text) { return embed(0, text); }

    @Override
    public float[] embed(long datasetId, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Embedding 文本不能为空");
        return cache.get(cacheKey(text), ignored -> {
            if (datasetId > 0) tokens.recordEmbedding(datasetId, text);
            return compute(text);
        });
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
        return List.copyOf(result);
    }

    protected float[] compute(String text) {
        return computeBatch(List.of(text)).get(0);
    }

    private List<float[]> computeBatch(List<String> texts) {
        String modelName = "ollama-embedding-" + OLLAMA_MODEL;
        if (!enabled) throw new IllegalStateException("Ollama Embedding 未启用，无法生成真实向量");
        ModelHealthRegistry registry = health.getIfAvailable();
        if (registry != null && !registry.available("EMBEDDING", modelName)) {
            throw new IllegalStateException("Embedding 模型暂时熔断: " + model);
        }
        try {
            List<float[]> values = embedding.embed(texts);
            if (values.size() != texts.size() || values.stream().anyMatch(value -> value == null || value.length != DIMENSIONS)) {
                throw new IllegalStateException("Embedding 期望 1024 维");
            }
            if (registry != null) registry.success("EMBEDDING", modelName);
            return values;
        } catch (RuntimeException error) {
            if (registry != null) registry.failure("EMBEDDING", modelName);
            throw new IllegalStateException("Ollama Embedding 调用失败", error);
        }
    }

    private String cacheKey(String text) { return model + ":" + DIMENSIONS + "\n" + text; }

    private String normalizeUrl(String value) {
        String text = value == null || value.isBlank() ? "http://127.0.0.1:11434" : value.trim();
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://" + text;
        return text.replaceAll("/$", "");
    }
}
