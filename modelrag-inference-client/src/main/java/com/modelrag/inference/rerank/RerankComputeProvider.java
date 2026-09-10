package com.modelrag.inference.rerank;

import java.time.Duration;
import java.util.List;

/** Stateless remote compute port used by Java's reranker policy. */
public interface RerankComputeProvider {
    List<RerankScore> rerank(String model, String query, List<RerankDocument> documents, Duration timeout);
}
