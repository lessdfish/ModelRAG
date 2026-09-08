package com.modelrag.knowledge.repository;

import com.modelrag.knowledge.model.Dataset;
import java.util.List;
import java.util.Set;

/** Persistence contract for the current dataset aggregate. */
public interface DatasetRepository {
    Dataset create(String name, String description, Integer chunkSize, Integer chunkOverlap);

    Dataset create(String name, String description, Integer chunkSize, Integer chunkOverlap,
            Integer topK, Double threshold);

    List<Dataset> findAll();

    Dataset findById(long id);

    Dataset update(long id, String name, String description);

    Dataset update(long id, String name, String description, Integer chunkSize, Integer chunkOverlap,
            Integer topK, Double threshold);

    void softDelete(long id);

    Dataset bumpRevision(long datasetId);

    List<Dataset> route(String query, Set<Long> allowedDatasetIds, int limit);

    Set<Long> findIndexedDatasetIds();
}
