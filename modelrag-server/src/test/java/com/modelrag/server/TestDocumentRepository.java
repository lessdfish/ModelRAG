package com.modelrag.server;

import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.service.InMemoryKnowledgeStore;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test adapter exposing the in-memory fixture through the narrow document contract. */
final class TestDocumentRepository implements DocumentRepository {
    private final InMemoryKnowledgeStore store;
    private final Map<Long, Long> activeVersions = new ConcurrentHashMap<>();
    private final Map<Long, Long> activeBuilds = new ConcurrentHashMap<>();

    TestDocumentRepository(InMemoryKnowledgeStore store) { this.store = store; }

    @Override public Document create(long datasetId, String name, String type, String hash, String content) {
        return store.createDocumentOnly(datasetId, name, type, hash, content, null, null, hash);
    }
    @Override public Document create(long datasetId, String name, String type, String hash, String content,
            String sourceObjectKey, String artifactObjectKey, String contentHash) {
        return store.createDocumentOnly(datasetId, name, type, hash, content,
                sourceObjectKey, artifactObjectKey, contentHash);
    }
    @Override public Document findById(long id) {
        Document document = store.document(id);
        return document.withActiveVersionId(activeVersions.get(id)).withActiveIndexBuildId(activeBuilds.get(id));
    }
    @Override public List<Document> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().filter(id -> id != null && id > 0).distinct()
                .map(this::findById).toList();
    }
    @Override public void activateVersion(long documentId, long documentVersionId) {
        store.document(documentId);
        activeVersions.put(documentId, documentVersionId);
    }
    @Override public List<Document> findByDatasetId(long datasetId) {
        return store.documents(datasetId).stream()
                .map(document -> document.withActiveVersionId(activeVersions.get(document.id()))
                        .withActiveIndexBuildId(activeBuilds.get(document.id()))).toList();
    }
    @Override public void activateIndexBuild(long documentId, long indexBuildId) {
        store.document(documentId);
        activeBuilds.put(documentId, indexBuildId);
    }
    @Override public void updateStatus(long documentId, String status, String error, int chunkCount) {
        store.status(documentId, status, error, chunkCount);
    }
    @Override public void softDelete(long documentId) {
        store.softDeleteDocumentOnly(documentId);
    }
    @Override public void softDeleteByDatasetId(long datasetId) { store.softDeleteDocumentsByDataset(datasetId); }
}
