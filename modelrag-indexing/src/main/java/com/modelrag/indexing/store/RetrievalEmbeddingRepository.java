package com.modelrag.indexing.store;

import java.util.Map;

/** Separate V2 vector-projection persistence contract; legacy VectorStore remains unchanged. */
public interface RetrievalEmbeddingRepository {
    void upsertBatch(long indexBuildId, String embeddingProfile, Map<Long, float[]> embeddingsByUnitId);

    long countByBuild(long indexBuildId);
}
