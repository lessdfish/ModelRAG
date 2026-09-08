package com.modelrag.knowledge.repository;

import java.util.Map;

/** Persistence contract for the current legacy document index-version lifecycle. */
public interface IndexVersionRepository {
    long begin(long documentId);

    void activate(long documentId, long version);

    Map<Long, Long> findActiveByDatasetId(long datasetId);

    Map<Long, Long> findAllActive();
}
