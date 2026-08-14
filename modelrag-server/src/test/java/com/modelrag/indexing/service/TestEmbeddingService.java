package com.modelrag.indexing.service;

import com.modelrag.common.cache.EmbeddingCache;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelHealthRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("test")
public class TestEmbeddingService extends EmbeddingService {
    public TestEmbeddingService(EmbeddingCache cache, TokenUsageTracker tokens, ObjectProvider<ModelHealthRegistry> health) {
        super(cache, false, "http://127.0.0.1:11434", "Qwen3-Embedding-0.6B", tokens, health);
    }
    @Override public float[] embed(String text) { return vector(text); }
    @Override public float[] embed(long datasetId, String text) { return vector(text); }
    @Override public List<float[]> embedBatch(long datasetId, List<String> texts) { return texts.stream().map(this::vector).toList(); }
    private float[] vector(String text) { float[] vector = new float[1024]; byte[] bytes = (text == null ? "" : text).toLowerCase().getBytes(StandardCharsets.UTF_8); for (int i = 0; i < bytes.length; i++) vector[(bytes[i] & 255) % vector.length] += i % 2 == 0 ? 1f : -1f; float norm = 0; for (float value : vector) norm += value * value; norm = (float) Math.sqrt(norm); if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= norm; return vector; }
}
