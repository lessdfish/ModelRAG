package com.modelrag.server;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitDraft;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory V2 retrieval-unit fixture; it mirrors bounded reads and pointer-derived visibility. */
final class TestRetrievalUnitRepository implements RetrievalUnitRepository {
    private static final int MAX_LIMIT = 500;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, RetrievalUnit> units = new LinkedHashMap<>();
    private final Map<Long, Aggregate> nodes = new HashMap<>();
    private final Map<Long, Aggregate> builds = new HashMap<>();
    private final Map<Long, Long> activeVersions = new HashMap<>();
    private final Map<Long, Long> activeBuilds = new HashMap<>();

    void registerNode(long nodeId, long datasetId, long documentId, long documentVersionId) {
        nodes.put(nodeId, new Aggregate(datasetId, documentId, documentVersionId, ""));
    }

    void registerBuild(long buildId, long datasetId, long documentId, long documentVersionId, String state) {
        builds.put(buildId, new Aggregate(datasetId, documentId, documentVersionId, state));
    }

    void activate(long documentId, long documentVersionId, long buildId) {
        activeVersions.put(documentId, documentVersionId);
        activeBuilds.put(documentId, buildId);
    }

    void setBuildState(long buildId, String state) {
        Aggregate build = builds.get(buildId);
        if (build == null) throw new IllegalArgumentException("unknown build");
        builds.put(buildId, new Aggregate(build.datasetId, build.documentId, build.documentVersionId, state));
    }

    @Override
    public synchronized List<RetrievalUnit> createBatch(List<RetrievalUnitDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) return List.of();
        List<RetrievalUnitDraft> pending = new ArrayList<>(drafts);
        for (RetrievalUnitDraft draft : pending) validate(draft);
        List<RetrievalUnit> result = new ArrayList<>(pending.size());
        for (RetrievalUnitDraft draft : pending) {
            String identity = identity(draft);
            if (units.values().stream().anyMatch(unit -> identity.equals(identity(unit)))) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "检索单元身份已存在");
            }
            RetrievalUnit unit = new RetrievalUnit(ids.incrementAndGet(), draft.datasetId(), draft.documentId(),
                    draft.documentVersionId(), draft.nodeId(), draft.indexBuildId(), draft.unitType(), draft.ordinal(),
                    draft.titlePath(), draft.content(), draft.contentHash(), draft.tokenCount(), draft.metadata(),
                    Instant.now());
            units.put(unit.id(), unit);
            result.add(unit);
        }
        return List.copyOf(result);
    }

    @Override public synchronized Optional<RetrievalUnit> findById(long unitId) {
        return Optional.ofNullable(units.get(unitId));
    }

    @Override public synchronized List<RetrievalUnit> findByBuild(long buildId, int offset, int limit) {
        return page(units.values().stream().filter(unit -> unit.indexBuildId() == buildId).toList(), offset, limit);
    }

    @Override public synchronized List<RetrievalUnit> findByNode(long nodeId, int offset, int limit) {
        return page(units.values().stream().filter(unit -> unit.nodeId() == nodeId).toList(), offset, limit);
    }

    @Override
    public synchronized List<RetrievalUnit> findActiveByIds(long datasetId, Collection<Long> unitIds) {
        if (unitIds == null || unitIds.isEmpty()) return List.of();
        if (unitIds.size() > MAX_LIMIT) throw new BusinessException(ErrorCode.VALIDATION, "读取范围过大");
        return unitIds.stream().map(units::get).filter(unit -> unit != null && unit.datasetId() == datasetId)
                .filter(unit -> activeVersions.get(unit.documentId()) != null
                        && activeVersions.get(unit.documentId()) == unit.documentVersionId())
                .filter(unit -> activeBuilds.get(unit.documentId()) != null
                        && activeBuilds.get(unit.documentId()) == unit.indexBuildId())
                .sorted(Comparator.comparingLong(RetrievalUnit::id)).toList();
    }

    @Override public synchronized long countByBuild(long buildId) {
        return units.values().stream().filter(unit -> unit.indexBuildId() == buildId).count();
    }

    private void validate(RetrievalUnitDraft draft) {
        if (draft == null || draft.unitType() == null || draft.datasetId() <= 0 || draft.documentId() <= 0
                || draft.documentVersionId() <= 0 || draft.nodeId() <= 0 || draft.indexBuildId() <= 0
                || draft.ordinal() < 0 || draft.tokenCount() < 0 || draft.content() == null
                || draft.content().isBlank() || draft.contentHash() == null || draft.contentHash().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元参数无效");
        }
        Aggregate node = nodes.get(draft.nodeId());
        Aggregate build = builds.get(draft.indexBuildId());
        if (node == null || build == null || !"UNIT_BUILDING".equals(build.state)
                || !node.matches(draft.datasetId(), draft.documentId(), draft.documentVersionId())
                || !build.matches(draft.datasetId(), draft.documentId(), draft.documentVersionId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索单元、节点和构建归属不一致");
        }
    }

    private List<RetrievalUnit> page(List<RetrievalUnit> values, int offset, int limit) {
        int boundedLimit = Math.min(MAX_LIMIT, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return values.stream().sorted(Comparator.comparingLong(RetrievalUnit::id))
                .skip(Math.max(0, offset)).limit(boundedLimit).toList();
    }

    private String identity(RetrievalUnitDraft draft) {
        return draft.indexBuildId() + ":" + draft.nodeId() + ":" + draft.unitType() + ":" + draft.ordinal();
    }

    private String identity(RetrievalUnit unit) {
        return unit.indexBuildId() + ":" + unit.nodeId() + ":" + unit.unitType() + ":" + unit.ordinal();
    }

    private record Aggregate(long datasetId, long documentId, long documentVersionId, String state) {
        private boolean matches(long dataset, long document, long version) {
            return datasetId == dataset && documentId == document && documentVersionId == version;
        }
    }
}
