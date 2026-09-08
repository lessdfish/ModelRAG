package com.modelrag.server;

import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.ChunkWindow;
import com.modelrag.knowledge.service.InMemoryKnowledgeStore;
import java.util.Collection;
import java.util.List;

/** Test adapter exposing the in-memory fixture through the narrow chunk contract. */
final class TestChunkRepository implements ChunkRepository {
    private final InMemoryKnowledgeStore store;

    TestChunkRepository(InMemoryKnowledgeStore store) { this.store = store; }

    @Override public List<Chunk> findActiveByDatasetId(long datasetId) { return store.chunks(datasetId); }
    @Override public List<Chunk> findActiveByIds(long datasetId, Collection<Long> ids) {
        return store.findChunksByIds(datasetId, ids);
    }
    @Override public List<Chunk> findActiveByParentIds(long datasetId, Collection<Long> parentIds) {
        return store.findChunksByParentIds(datasetId, parentIds);
    }
    @Override public List<Chunk> findActiveNeighbors(long datasetId, Collection<ChunkWindow> windows) {
        List<com.modelrag.knowledge.service.KnowledgeStore.ChunkWindow> legacy = windows == null ? List.of() : windows.stream()
                .map(window -> new com.modelrag.knowledge.service.KnowledgeStore.ChunkWindow(
                        window.documentId(), window.fromIndex(), window.toIndex())).toList();
        return store.findChunkNeighbors(datasetId, legacy);
    }
    @Override public void replaceDocumentVersion(long documentId, List<Chunk> chunks) { store.replaceChunksOnly(documentId, chunks); }
    @Override public void beginDocumentVersion(long documentId, long version) { store.beginChunks(documentId, version); }
    @Override public void append(long documentId, List<Chunk> chunks) { store.appendChunks(documentId, chunks); }
    @Override public void softDeleteByDocumentId(long documentId) { store.softDeleteChunksByDocument(documentId); }
    @Override public void softDeleteByDatasetId(long datasetId) { store.softDeleteChunksByDataset(datasetId); }
    @Override public long nextId() { return store.nextId(); }
}
