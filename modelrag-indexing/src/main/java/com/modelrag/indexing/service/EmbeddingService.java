package com.modelrag.indexing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.cache.EmbeddingCache;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelHealthRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Uses the configured local Ollama embedding model first; deterministic vectors only keep offline tests reproducible. */
@Service
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private final EmbeddingCache cache;
    private final boolean ollamaEnabled;
    private final String ollamaUrl;
    private final String model;
    private final TokenUsageTracker tokens;
    private final ObjectProvider<ModelHealthRegistry> health;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public EmbeddingService(EmbeddingCache cache,
            @Value("${modelrag.ollama.enabled:true}") boolean enabled,
            @Value("${modelrag.ollama.url:http://127.0.0.1:11434}") String url,
            @Value("${modelrag.ollama.embedding-model:quentinz/bge-large-zh-v1.5}") String model,
            TokenUsageTracker tokens, ObjectProvider<ModelHealthRegistry> health) {
        this.cache = cache; ollamaEnabled = enabled; ollamaUrl = normalizeUrl(url); this.model = model; this.tokens = tokens; this.health = health;
    }

    public float[] embed(String text) {
        return embed(0,text);
    }

    public float[] embed(long datasetId,String text) {
        return cache.get((ollamaEnabled ? model : "deterministic") + "\n" + text, ignored -> {
            if (datasetId > 0) tokens.recordEmbedding(datasetId,text);
            return compute(text);
        });
    }

    private float[] compute(String text) {
        String modelName = "ollama-embedding-" + model;
        ModelHealthRegistry registry = health.getIfAvailable();
        if (ollamaEnabled && (registry == null || registry.available("EMBEDDING", modelName))) try {
            float[] vector = ollama(text);
            if (registry != null) registry.success("EMBEDDING", modelName);
            return vector;
        } catch (RuntimeException error) {
            if (registry != null) registry.failure("EMBEDDING", modelName);
            log.warn("Ollama Embedding unavailable, using deterministic local embedding: {}", error.getMessage());
        }
        return deterministic(text);
    }

    private float[] deterministic(String text) {
        float[] vector = new float[1024];
        byte[] bytes = text.toLowerCase().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) vector[(bytes[i] & 255) % vector.length] += i % 2 == 0 ? 1f : -1f;
        float norm = 0;
        for (float value : vector) norm += value * value;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }

    private float[] ollama(String text) {
        try {
            String body = json.writeValueAsString(Map.of("model", model, "input", text));
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaUrl + "/api/embed"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
            JsonNode vector = json.readTree(response.body()).path("embeddings").path(0);
            if (vector.size() != 1024) throw new IllegalStateException("期望 1024 维，实际 " + vector.size());
            float[] result = new float[1024];
            for (int i = 0; i < result.length; i++) result[i] = (float) vector.get(i).asDouble();
            return result;
        } catch (Exception error) { throw new IllegalStateException("Ollama Embedding 调用失败", error); }
    }

    private String normalizeUrl(String value) {
        String text = value == null || value.isBlank() ? "127.0.0.1:11434" : value.trim();
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://" + text;
        return text.replaceAll("/$", "");
    }
}
