package com.modelrag.server;

import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.service.InMemoryKnowledgeStore;
import java.util.List;

/** Test adapter exposing the in-memory fixture through the narrow document contract. */
final class TestDocumentRepository implements DocumentRepository {
    private final InMemoryKnowledgeStore store;

    TestDocumentRepository(InMemoryKnowledgeStore store) { this.store = store; }

    @Override public Document create(long datasetId, String name, String type, String hash, String content) {
        return store.createDocumentOnly(datasetId, name, type, hash, content, null, null, hash);
    }
    @Override public Document create(long datasetId, String name, String type, String hash, String content,
            String sourceObjectKey, String artifactObjectKey, String contentHash) {
        return store.createDocumentOnly(datasetId, name, type, hash, content,
                sourceObjectKey, artifactObjectKey, contentHash);
    }
    @Override public Document findById(long id) { return store.document(id); }
    @Override public List<Document> findByDatasetId(long datasetId) { return store.documents(datasetId); }
    @Override public void updateStatus(long documentId, String status, String error, int chunkCount) {
        store.status(documentId, status, error, chunkCount);
    }
    @Override public void softDelete(long documentId) {
        store.softDeleteDocumentOnly(documentId);
    }
    @Override public void softDeleteByDatasetId(long datasetId) { store.softDeleteDocumentsByDataset(datasetId); }
}
