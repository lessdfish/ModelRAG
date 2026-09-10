package com.modelrag.inference.embedding;

import java.time.Duration;
import java.util.List;

/** Java port for bounded embedding computation; it carries no dataset or user context. */
public interface EmbeddingComputeProvider {
    List<float[]> embed(String profile, int dimensions, List<String> texts, Duration timeout);
}
