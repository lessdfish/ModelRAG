package com.modelrag.server;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory V2 build fixture with compare-and-set state transitions. */
final class TestIndexBuildRepository implements IndexBuildRepository {
    private static final int MAX_LIMIT = 500;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, IndexBuild> builds = new LinkedHashMap<>();

    @Override
    public synchronized IndexBuild create(long datasetId, long documentId, long documentVersionId,
            String embeddingProfile, String rerankProfile, Map<String, Object> metadata) {
        long buildNo = builds.values().stream().filter(build -> build.documentId() == documentId)
                .mapToLong(IndexBuild::buildNo).max().orElse(0) + 1;
        IndexBuild build = new IndexBuild(ids.incrementAndGet(), datasetId, documentId, documentVersionId,
                buildNo, IndexBuildState.CREATED, embeddingProfile, rerankProfile, 0, 0, 0, 0, null,
                metadata, Instant.now(), null, null, null, null);
        builds.put(build.id(), build);
        return build;
    }

    @Override public synchronized Optional<IndexBuild> findById(long buildId) {
        return Optional.ofNullable(builds.get(buildId));
    }

    @Override public synchronized Optional<IndexBuild> findActiveByDocumentId(long documentId) {
        return builds.values().stream().filter(build -> build.documentId() == documentId
                && build.state() == IndexBuildState.ACTIVE).findFirst();
    }

    @Override
    public synchronized List<IndexBuild> findByDocumentId(long documentId, int offset, int limit) {
        int boundedLimit = Math.min(MAX_LIMIT, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return builds.values().stream().filter(build -> build.documentId() == documentId)
                .sorted(Comparator.comparingLong(IndexBuild::buildNo).reversed())
                .skip(Math.max(0, offset)).limit(boundedLimit).toList();
    }

    @Override
    public synchronized List<IndexBuild> findByState(IndexBuildState state, int limit) {
        int boundedLimit = Math.min(MAX_LIMIT, Math.max(0, limit));
        if (state == null || boundedLimit == 0) return List.of();
        return builds.values().stream().filter(build -> build.state() == state)
                .sorted(Comparator.comparingLong(IndexBuild::id)).limit(boundedLimit).toList();
    }

    @Override
    public synchronized boolean transition(long buildId, IndexBuildState expected, IndexBuildState next) {
        if (expected == null || next == null || !expected.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.VALIDATION, "非法的 IndexBuild 状态转移");
        }
        IndexBuild current = builds.get(buildId);
        if (current == null || current.state() != expected) return false;
        Instant now = Instant.now();
        builds.put(buildId, copy(current, next,
                current.startTime() == null && next == IndexBuildState.PARSING ? now : current.startTime(),
                next == IndexBuildState.READY ? now : current.readyTime(),
                next == IndexBuildState.ACTIVE ? now : current.activeTime(),
                next == IndexBuildState.FAILED ? now : current.failedTime(), current.errorMsg()));
        return true;
    }

    @Override
    public synchronized void updateCounts(long buildId, long nodeCount, long unitCount,
            long vectorCount, long lexicalCount) {
        if (nodeCount < 0 || unitCount < 0 || vectorCount < 0 || lexicalCount < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "IndexBuild 计数无效");
        }
        IndexBuild current = require(buildId);
        builds.put(buildId, new IndexBuild(current.id(), current.datasetId(), current.documentId(),
                current.documentVersionId(), current.buildNo(), current.state(), current.embeddingProfile(),
                current.rerankProfile(), nodeCount, unitCount, vectorCount, lexicalCount, current.errorMsg(),
                current.metadata(), current.createTime(), current.startTime(), current.readyTime(),
                current.activeTime(), current.failedTime()));
    }

    @Override
    public synchronized void markFailed(long buildId, String safeError) {
        IndexBuild current = require(buildId);
        if (current.state() == IndexBuildState.ACTIVE || current.state() == IndexBuildState.SUPERSEDED) {
            throw new BusinessException(ErrorCode.VALIDATION, "活动或已替代构建不能失败");
        }
        builds.put(buildId, copy(current, IndexBuildState.FAILED, current.startTime(), current.readyTime(),
                current.activeTime(), Instant.now(), safeError));
    }

    private IndexBuild require(long buildId) {
        IndexBuild value = builds.get(buildId);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND, "IndexBuild 不存在");
        return value;
    }

    private IndexBuild copy(IndexBuild current, IndexBuildState state, Instant startTime, Instant readyTime,
            Instant activeTime, Instant failedTime, String errorMsg) {
        return new IndexBuild(current.id(), current.datasetId(), current.documentId(), current.documentVersionId(),
                current.buildNo(), state, current.embeddingProfile(), current.rerankProfile(), current.nodeCount(),
                current.unitCount(), current.vectorCount(), current.lexicalCount(), errorMsg, current.metadata(),
                current.createTime(), startTime, readyTime, activeTime, failedTime);
    }
}
