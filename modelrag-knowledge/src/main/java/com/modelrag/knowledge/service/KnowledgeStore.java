package com.modelrag.knowledge.service;

import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Business data contract. Production implementations must use PostgreSQL. */
public interface KnowledgeStore {
    Dataset createDataset(String name, String description, Integer chunkSize, Integer chunkOverlap);

    Dataset createDataset(String name, String description, Integer chunkSize, Integer chunkOverlap,
            Integer topK, Double threshold);

    List<Dataset> datasets();
    /** Bounded metadata/routing-vector preselection. It must not execute document retrieval per dataset. */
    default List<Dataset> routeDatasets(String query, Set<Long> allowedDatasetIds, int limit) {
        Set<Long> indexed = indexedDatasetIds();
        return datasets().stream()
                .filter(dataset -> allowedDatasetIds == null || allowedDatasetIds.isEmpty()
                        || allowedDatasetIds.contains(dataset.id()))
                .filter(dataset -> indexed.contains(dataset.id()))
                .sorted(java.util.Comparator.comparingInt((Dataset dataset) -> metadataScore(dataset, query)).reversed()
                        .thenComparing(Dataset::id))
                .limit(Math.max(1, Math.min(3, limit)))
                .toList();
    }
    default Set<Long> indexedDatasetIds() {
        return datasets().stream().filter(dataset -> !chunks(dataset.id()).isEmpty()).map(Dataset::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
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
    /** Starts a new build without changing the currently active index version. */
    default long beginIndexVersion(long documentId) { return 1L; }
    default void activateIndexVersion(long documentId, long version) { }
    default Map<Long, Long> activeIndexVersions(long datasetId) { return Map.of(); }
    /** Returns the active physical-index version for every live document. */
    default Map<Long, Long> allActiveIndexVersions() { return Map.of(); }
    List<Document> documents(long datasetId);
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

    private static int metadataScore(Dataset dataset, String query) {
        String source = query == null ? "" : query.replaceAll("[\\s，。！？、：:]+", "");
        String target = dataset.name() + " " + (dataset.description() == null ? "" : dataset.description());
        int score = 0;
        for (int index = 0; index + 1 < source.length(); index++) {
            if (target.contains(source.substring(index, index + 2))) score++;
        }
        return score;
    }
}
