package com.modelrag.server;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.indexing.store.RetrievalEmbeddingRepository;
import com.modelrag.knowledge.model.RetrievalUnit;
import java.util.HashMap;
import java.util.Map;

/** In-memory V2 vector projection fixture with unit-derived identity fields. */
final class TestRetrievalEmbeddingRepository implements RetrievalEmbeddingRepository {
    private final TestRetrievalUnitRepository units;
    private final Map<Key, Row> rows = new HashMap<>();

    TestRetrievalEmbeddingRepository(TestRetrievalUnitRepository units) {
        this.units = units;
    }

    @Override
    public synchronized void upsertBatch(long indexBuildId, String embeddingProfile,
            Map<Long, float[]> embeddingsByUnitId) {
        if (indexBuildId <= 0 || embeddingProfile == null || embeddingProfile.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "向量构建或 profile 无效");
        }
        if (embeddingsByUnitId == null) return;
        for (Map.Entry<Long, float[]> entry : embeddingsByUnitId.entrySet()) {
            float[] embedding = entry.getValue();
            if (embedding == null || embedding.length != 1024) {
                throw new BusinessException(ErrorCode.VALIDATION, "向量维度无效");
            }
            RetrievalUnit unit = units.findById(entry.getKey()).orElseThrow(
                    () -> new BusinessException(ErrorCode.NOT_FOUND, "检索单元不存在"));
            if (unit.indexBuildId() != indexBuildId) {
                throw new BusinessException(ErrorCode.VALIDATION, "向量单元不属于指定构建");
            }
            rows.put(new Key(unit.id(), embeddingProfile),
                    new Row(unit.datasetId(), unit.documentId(), unit.documentVersionId(), embedding.clone()));
        }
    }

    @Override public synchronized long countByBuild(long indexBuildId) {
        return rows.keySet().stream().filter(key -> units.findById(key.unitId())
                .map(unit -> unit.indexBuildId() == indexBuildId).orElse(false)).count();
    }

    Row row(long unitId, String profile) { return rows.get(new Key(unitId, profile)); }

    record Row(long datasetId, long documentId, long documentVersionId, float[] embedding) { }
    private record Key(long unitId, String profile) { }
}
