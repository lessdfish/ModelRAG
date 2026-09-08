package com.modelrag.knowledge.repository;

import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence contract for V2 index-build lifecycle and bounded history reads. */
public interface IndexBuildRepository {
    IndexBuild create(long datasetId, long documentId, long documentVersionId,
            String embeddingProfile, String rerankProfile, Map<String, Object> metadata);

    Optional<IndexBuild> findById(long buildId);

    Optional<IndexBuild> findActiveByDocumentId(long documentId);

    List<IndexBuild> findByDocumentId(long documentId, int offset, int limit);

    /** Bounded worker query; there is intentionally no dataset-wide findAll operation. */
    default List<IndexBuild> findByState(IndexBuildState state, int limit) { return List.of(); }

    boolean transition(long buildId, IndexBuildState expected, IndexBuildState next);

    void updateCounts(long buildId, long nodeCount, long unitCount, long vectorCount, long lexicalCount);

    void markFailed(long buildId, String safeError);
}
