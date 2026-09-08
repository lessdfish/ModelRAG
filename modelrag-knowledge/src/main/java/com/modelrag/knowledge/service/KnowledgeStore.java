package com.modelrag.knowledge.service;

import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @deprecated Use the narrow repository contracts in
 *             {@code com.modelrag.knowledge.repository}. This compatibility
 *             facade remains only for one migration step and is not a
 *             production dependency.
 */
@Deprecated
public interface KnowledgeStore {
    Dataset createDataset(String name, String description, Integer chunkSize, Integer chunkOverlap);

    Dataset createDataset(String name, String description, Integer chunkSize, Integer chunkOverlap,
            Integer topK, Double threshold);

    List<Dataset> datasets();

    List<Dataset> routeDatasets(String query, Set<Long> allowedDatasetIds, int limit);

    Set<Long> indexedDatasetIds();

    Dataset dataset(long id);

    Dataset updateDataset(long id, String name, String description);

    Dataset updateDataset(long id, String name, String description, Integer chunkSize, Integer chunkOverlap,
            Integer topK, Double threshold);

    void deleteDataset(long id);

    Document addDocument(long datasetId, String name, String type, String hash, String content);

    Document addDocument(long datasetId, String name, String type, String hash, String content,
            String sourceObjectKey, String artifactObjectKey, String contentHash);

    Document document(long id);

    void deleteDocument(long datasetId, long documentId);

    void status(long id, String status, String error, int chunkCount);

    default long beginIndexVersion(long documentId) { return 1L; }

    default void activateIndexVersion(long documentId, long version) { }

    default Map<Long, Long> activeIndexVersions(long datasetId) { return Map.of(); }

    /** Returns the active physical-index version for every live document. */
    default Map<Long, Long> allActiveIndexVersions() { return Map.of(); }

    List<Document> documents(long datasetId);

    /** Legacy/offline operation. Never use this method in an online retrieval hot path. */
    List<Chunk> chunks(long datasetId);

    List<Chunk> findChunksByIds(long datasetId, Collection<Long> ids);

    List<Chunk> findChunksByParentIds(long datasetId, Collection<Long> parentIds);

    List<Chunk> findChunkNeighbors(long datasetId, Collection<ChunkWindow> windows);

    void chunks(long documentId, List<Chunk> chunks);

    /** Clears only the new index version before bounded windows are appended. */
    default void beginChunks(long documentId, long version) { }

    /** Appends one bounded indexing window without replacing prior windows. */
    default void appendChunks(long documentId, List<Chunk> chunks) { chunks(documentId, chunks); }

    long nextId();

    Dataset bumpDatasetRevision(long datasetId);

    record ChunkWindow(long documentId, int fromIndex, int toIndex) {
        public ChunkWindow {
            if (fromIndex > toIndex) throw new IllegalArgumentException("chunk window is inverted");
        }
    }
}
