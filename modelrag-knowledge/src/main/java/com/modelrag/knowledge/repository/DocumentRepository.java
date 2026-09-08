package com.modelrag.knowledge.repository;

import com.modelrag.knowledge.model.Document;
import java.util.Collection;
import java.util.List;

/** Persistence contract for the current logical document aggregate. */
public interface DocumentRepository {
    Document create(long datasetId, String name, String type, String hash, String content);

    Document create(long datasetId, String name, String type, String hash, String content,
            String sourceObjectKey, String artifactObjectKey, String contentHash);

    Document findById(long id);

    /** Bounded batch lookup used by V2 evidence assembly; implementations must not enumerate a dataset. */
    default List<Document> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().filter(id -> id != null && id > 0).distinct().map(this::findById).toList();
    }

    void activateVersion(long documentId, long documentVersionId);

    /** Locks the logical document row for an atomic V2 build cutover. */
    default void lockForIndexBuildActivation(long documentId) { }

    void activateIndexBuild(long documentId, long indexBuildId);

    List<Document> findByDatasetId(long datasetId);

    void updateStatus(long documentId, String status, String error, int chunkCount);

    void softDelete(long documentId);

    void softDeleteByDatasetId(long datasetId);
}
