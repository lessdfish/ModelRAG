package com.modelrag.indexing.pipeline;

/** Small immutable orchestration context; it never contains a whole document or projection page. */
public record IndexBuildContext(long buildId, long datasetId, long documentId, long documentVersionId,
        String embeddingProfile, String rerankProfile, String sourceObjectKey,
        long nodeCount, long unitCount, long vectorCount, long lexicalCount) {
    public IndexBuildContext {
        if (buildId <= 0 || datasetId <= 0 || documentId <= 0 || documentVersionId <= 0) {
            throw new IllegalArgumentException("IndexBuildContext 标识无效");
        }
        if (embeddingProfile == null || embeddingProfile.isBlank()) {
            throw new IllegalArgumentException("embedding profile 不能为空");
        }
        if (nodeCount < 0 || unitCount < 0 || vectorCount < 0 || lexicalCount < 0) {
            throw new IllegalArgumentException("IndexBuildContext 计数无效");
        }
    }

    public IndexBuildContext withCounts(long nodes, long units, long vectors, long lexical) {
        return new IndexBuildContext(buildId, datasetId, documentId, documentVersionId, embeddingProfile,
                rerankProfile, sourceObjectKey, nodes, units, vectors, lexical);
    }
}
