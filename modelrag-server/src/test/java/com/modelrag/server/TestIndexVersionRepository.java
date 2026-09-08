package com.modelrag.server;

import com.modelrag.knowledge.repository.IndexVersionRepository;
import com.modelrag.knowledge.service.InMemoryKnowledgeStore;
import java.util.Map;

/** Test adapter exposing the in-memory fixture through the narrow version contract. */
final class TestIndexVersionRepository implements IndexVersionRepository {
    private final InMemoryKnowledgeStore store;

    TestIndexVersionRepository(InMemoryKnowledgeStore store) { this.store = store; }

    @Override public long begin(long documentId) { return store.beginIndexVersion(documentId); }
    @Override public void activate(long documentId, long version) { store.activateIndexVersion(documentId, version); }
    @Override public Map<Long, Long> findActiveByDatasetId(long datasetId) {
        return store.activeIndexVersions(datasetId);
    }
    @Override public Map<Long, Long> findAllActive() { return store.allActiveIndexVersions(); }
}
