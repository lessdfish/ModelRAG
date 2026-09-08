package com.modelrag.indexing.store;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for V2 embeddings, kept separate from the legacy chunk VectorStore. */
@Repository
@Profile("!test")
public class JdbcRetrievalEmbeddingRepository implements RetrievalEmbeddingRepository {
    private static final int VECTOR_DIMENSIONS = 1024;
    private static final int WRITE_BATCH_SIZE = 500;
    private final JdbcTemplate jdbc;

    public JdbcRetrievalEmbeddingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsertBatch(long indexBuildId, String embeddingProfile, Map<Long, float[]> embeddingsByUnitId) {
        if (indexBuildId <= 0 || embeddingProfile == null || embeddingProfile.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "向量构建或 embedding profile 无效");
        }
        if (embeddingsByUnitId == null || embeddingsByUnitId.isEmpty()) return;
        List<Map.Entry<Long, float[]>> entries = new ArrayList<>(embeddingsByUnitId.entrySet());
        for (int start = 0; start < entries.size(); start += WRITE_BATCH_SIZE) {
            int end = Math.min(entries.size(), start + WRITE_BATCH_SIZE);
            for (Map.Entry<Long, float[]> entry : entries.subList(start, end)) {
                validateVector(entry.getKey(), entry.getValue());
                ensureVectorBuildIsWritable(indexBuildId);
                try {
                    int updated = jdbc.update("""
                            INSERT INTO kb_vector_embedding(
                                retrieval_unit_id,dataset_id,document_id,document_version_id,index_build_id,
                                embedding_profile,embedding)
                            SELECT u.id,u.dataset_id,u.document_id,u.document_version_id,u.index_build_id,
                                   ?,CAST(? AS vector)
                            FROM kb_retrieval_unit u
                            WHERE u.id=? AND u.index_build_id=?
                            ON CONFLICT (retrieval_unit_id,embedding_profile)
                            DO UPDATE SET embedding=EXCLUDED.embedding,create_time=NOW()
                            """, embeddingProfile, vector(entry.getValue()), entry.getKey(), indexBuildId);
                    if (updated != 1) {
                        throw new BusinessException(ErrorCode.VALIDATION,
                                "向量单元不属于指定构建");
                    }
                } catch (DataIntegrityViolationException error) {
                    throw new BusinessException(ErrorCode.VALIDATION,
                            "向量单元关联对象无效");
                }
            }
        }
    }

    @Override
    public long countByBuild(long indexBuildId) {
        if (indexBuildId <= 0) return 0;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_vector_embedding WHERE index_build_id=?",
                Long.class, indexBuildId);
        return count == null ? 0 : count;
    }

    private void ensureVectorBuildIsWritable(long indexBuildId) {
        String state = jdbc.query("SELECT state FROM kb_index_build WHERE id=?",
                (rs, row) -> rs.getString(1), indexBuildId).stream().findFirst().orElse(null);
        if (state == null) throw new BusinessException(ErrorCode.NOT_FOUND, "向量构建不存在");
        if (!"VECTOR_BUILDING".equals(state)) {
            throw new BusinessException(ErrorCode.VALIDATION, "只有 VECTOR_BUILDING 构建可以写入向量");
        }
    }

    private void validateVector(Long unitId, float[] values) {
        if (unitId == null || unitId <= 0 || values == null || values.length != VECTOR_DIMENSIONS) {
            throw new BusinessException(ErrorCode.VALIDATION, "向量维度必须为 1024");
        }
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new BusinessException(ErrorCode.VALIDATION, "向量包含非法数值");
            }
        }
    }

    private String vector(float[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) result.append(',');
            result.append(Float.toString(values[index]));
        }
        return result.append(']').toString();
    }
}
