package com.modelrag.knowledge.repository.jdbc;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.repository.IndexVersionRepository;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for the current legacy document index-version lifecycle. */
@Repository
@Profile("!test")
public class JdbcIndexVersionRepository implements IndexVersionRepository {
    private final JdbcTemplate jdbc;

    public JdbcIndexVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long begin(long documentId) {
        Long version = jdbc.queryForObject("""
                UPDATE kb_document
                SET version=version+1,index_status='BUILDING',index_state='BUILDING',error_msg=NULL,update_time=NOW()
                WHERE id=? AND delete_time IS NULL
                RETURNING version
                """, Long.class, documentId);
        if (version == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        return version;
    }

    @Override
    public void activate(long documentId, long version) {
        jdbc.update("""
                UPDATE kb_document SET active_index_version=?,index_status='READY',index_state='READY',
                error_msg=NULL,update_time=NOW() WHERE id=? AND version=? AND delete_time IS NULL
                """, version, documentId, version);
    }

    @Override
    public Map<Long, Long> findActiveByDatasetId(long datasetId) {
        return jdbc.query("""
                SELECT id,active_index_version FROM kb_document
                WHERE dataset_id=? AND delete_time IS NULL AND active_index_version > 0
                """, (rs, n) -> Map.entry(rs.getLong(1), rs.getLong(2)), datasetId)
                .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Map<Long, Long> findAllActive() {
        return jdbc.query("""
                SELECT id,active_index_version FROM kb_document
                WHERE delete_time IS NULL AND active_index_version > 0
                """, (rs, n) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
