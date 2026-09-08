package com.modelrag.knowledge.repository;

import com.modelrag.knowledge.model.Chunk;
import java.util.Collection;
import java.util.List;

/** Persistence contract for legacy chunk storage and bounded retrieval access. */
public interface ChunkRepository {
    /** Legacy/offline operation. Never use this method in an online retrieval hot path. */
    List<Chunk> findActiveByDatasetId(long datasetId);

    List<Chunk> findActiveByIds(long datasetId, Collection<Long> ids);

    List<Chunk> findActiveByParentIds(long datasetId, Collection<Long> parentIds);

    List<Chunk> findActiveNeighbors(long datasetId, Collection<ChunkWindow> windows);

    void replaceDocumentVersion(long documentId, List<Chunk> chunks);

    void beginDocumentVersion(long documentId, long version);

    void append(long documentId, List<Chunk> chunks);

    void softDeleteByDocumentId(long documentId);

    void softDeleteByDatasetId(long datasetId);

    long nextId();
}
