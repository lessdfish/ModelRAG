package com.modelrag.knowledge.repository;

import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitDraft;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Bounded persistence contract for V2 retrieval projections. */
public interface RetrievalUnitRepository {
    List<RetrievalUnit> createBatch(List<RetrievalUnitDraft> units);

    Optional<RetrievalUnit> findById(long unitId);

    List<RetrievalUnit> findByBuild(long buildId, int offset, int limit);

    List<RetrievalUnit> findByNode(long nodeId, int offset, int limit);

    List<RetrievalUnit> findActiveByIds(long datasetId, Collection<Long> unitIds);

    long countByBuild(long buildId);
}
