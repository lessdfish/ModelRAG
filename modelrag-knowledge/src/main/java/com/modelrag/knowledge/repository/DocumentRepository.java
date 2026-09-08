package com.modelrag.knowledge.repository;

import com.modelrag.knowledge.model.Document;
import java.util.List;

/** Persistence contract for the current logical document aggregate. */
public interface DocumentRepository {
    Document create(long datasetId, String name, String type, String hash, String content);

    Document create(long datasetId, String name, String type, String hash, String content,
            String sourceObjectKey, String artifactObjectKey, String contentHash);

    Document findById(long id);

    void activateVersion(long documentId, long documentVersionId);

    List<Document> findByDatasetId(long datasetId);

    void updateStatus(long documentId, String status, String error, int chunkCount);

    void softDelete(long documentId);

    void softDeleteByDatasetId(long datasetId);
}
