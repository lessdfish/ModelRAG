package com.modelrag.knowledge.repository;

import com.modelrag.knowledge.model.DocumentParseStatus;
import com.modelrag.knowledge.model.DocumentVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence contract for immutable document content snapshots. */
public interface DocumentVersionRepository {
    DocumentVersion create(long documentId, String sourceHash, String sourceObjectKey,
            String artifactObjectKey, String contentHash, String parserName, String parserVersion,
            DocumentParseStatus parseStatus, Map<String, Object> metadata);

    Optional<DocumentVersion> findById(long id);

    Optional<DocumentVersion> findActiveByDocumentId(long documentId);

    List<DocumentVersion> findByDocumentId(long documentId);

    /** Locks the immutable version row for one short structure-persistence transaction. */
    void lockForStructure(long documentVersionId);
}
