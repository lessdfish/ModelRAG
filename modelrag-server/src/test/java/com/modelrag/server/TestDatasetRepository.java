package com.modelrag.server;

import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.knowledge.service.InMemoryKnowledgeStore;
import java.util.List;
import java.util.Set;

/** Test adapter exposing the in-memory fixture through the narrow dataset contract. */
final class TestDatasetRepository implements DatasetRepository {
    private final InMemoryKnowledgeStore store;

    TestDatasetRepository(InMemoryKnowledgeStore store) { this.store = store; }

    @Override public Dataset create(String name, String description, Integer chunkSize, Integer chunkOverlap) {
        return store.createDataset(name, description, chunkSize, chunkOverlap);
    }
    @Override public Dataset create(String name, String description, Integer chunkSize, Integer chunkOverlap,
            Integer topK, Double threshold) {
        return store.createDataset(name, description, chunkSize, chunkOverlap, topK, threshold);
    }
    @Override public List<Dataset> findAll() { return store.datasets(); }
    @Override public Dataset findById(long id) { return store.dataset(id); }
    @Override public Dataset update(long id, String name, String description) {
        return store.updateDataset(id, name, description);
    }
    @Override public Dataset update(long id, String name, String description, Integer chunkSize, Integer chunkOverlap,
            Integer topK, Double threshold) {
        return store.updateDataset(id, name, description, chunkSize, chunkOverlap, topK, threshold);
    }
    @Override public void softDelete(long id) { store.softDeleteDatasetOnly(id); }
    @Override public Dataset bumpRevision(long datasetId) { return store.bumpDatasetRevision(datasetId); }
    @Override public List<Dataset> route(String query, Set<Long> allowedDatasetIds, int limit) {
        return store.routeDatasets(query, allowedDatasetIds, limit);
    }
    @Override public Set<Long> findIndexedDatasetIds() { return store.indexedDatasetIds(); }
}
