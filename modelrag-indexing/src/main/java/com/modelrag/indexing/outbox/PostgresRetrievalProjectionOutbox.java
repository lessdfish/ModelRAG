package com.modelrag.indexing.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.RetrievalUnit;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL V2 projection outbox; it never reuses the chunk-shaped V1 event contract. */
@Service
@Profile("!test")
public class PostgresRetrievalProjectionOutbox implements RetrievalProjectionOutbox {
    private static final int MAX_CLAIM = 100;
    private static final int MAX_RETRIES = 5;
    private static final String EVENT_TYPE = "UPSERT_RETRIEVAL_UNIT_V2";
    private static final String EVENT_COLUMNS = "id,event_type,dataset_id,document_id,document_version_id,"
            + "index_build_id,retrieval_unit_id,payload,status,retry_count,next_retry_at,lease_until,"
            + "dead_letter,error_msg,idempotency_key";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Autowired
    public PostgresRetrievalProjectionOutbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void appendBatch(long buildId, List<RetrievalUnit> units) {
        if (buildId <= 0) throw new BusinessException(ErrorCode.VALIDATION, "V2 构建 ID 无效");
        if (units == null || units.isEmpty()) return;
        for (RetrievalUnit unit : units) {
            if (unit == null || unit.indexBuildId() != buildId) {
                throw new BusinessException(ErrorCode.VALIDATION, "V2 检索单元不属于指定构建");
            }
            String idempotencyKey = EVENT_TYPE + ":" + buildId + ":" + unit.id();
            jdbc.update("""
                    INSERT INTO kb_retrieval_projection_outbox(
                        event_type,dataset_id,document_id,document_version_id,index_build_id,
                        retrieval_unit_id,payload,status,retry_count,next_retry_at,idempotency_key)
                    VALUES (?,?,?,?,?,?,CAST(? AS jsonb),'PENDING',0,NOW(),?)
                    ON CONFLICT (idempotency_key) DO UPDATE SET update_time=NOW()
                    """, EVENT_TYPE, unit.datasetId(), unit.documentId(), unit.documentVersionId(),
                    unit.indexBuildId(), unit.id(), payload(unit), idempotencyKey);
        }
    }

    @Override
    public List<RetrievalProjectionOutboxEvent> claimDue(int limit) {
        int boundedLimit = Math.min(MAX_CLAIM, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return jdbc.query("""
                WITH claimed AS (
                    SELECT id
                    FROM kb_retrieval_projection_outbox
                    WHERE dead_letter=FALSE
                      AND ((status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=NOW()))
                           OR (status='PROCESSING' AND lease_until<NOW()))
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE kb_retrieval_projection_outbox o
                SET status='PROCESSING',lease_until=NOW()+INTERVAL '60 seconds',update_time=NOW()
                FROM claimed c
                WHERE o.id=c.id
                RETURNING o.id,o.event_type,o.dataset_id,o.document_id,o.document_version_id,
                          o.index_build_id,o.retrieval_unit_id,o.payload,o.status,o.retry_count,
                          o.next_retry_at,o.lease_until,o.dead_letter,o.error_msg,o.idempotency_key
                """, (rs, row) -> event(rs), boundedLimit);
    }

    @Override
    public void markDone(long id) {
        if (id <= 0) return;
        jdbc.update("""
                UPDATE kb_retrieval_projection_outbox
                SET status='DONE',next_retry_at=NULL,lease_until=NULL,dead_letter=FALSE,error_msg=NULL,update_time=NOW()
                WHERE id=?
                """, id);
    }

    @Override
    public void markFailed(long id, String safeError) {
        if (id <= 0) return;
        String message = safeError == null ? "" : safeError.substring(0, Math.min(2000, safeError.length()));
        jdbc.update("""
                UPDATE kb_retrieval_projection_outbox
                SET retry_count=retry_count+1,
                    status=CASE WHEN retry_count+1>=? THEN 'FAILED' ELSE 'PENDING' END,
                    next_retry_at=CASE WHEN retry_count+1>=? THEN NULL
                        ELSE NOW() + (power(2, LEAST(retry_count+1,6)) * INTERVAL '1 second') END,
                    lease_until=NULL,
                    dead_letter=(retry_count+1>=?),
                    error_msg=?,update_time=NOW()
                WHERE id=?
                """, MAX_RETRIES, MAX_RETRIES, MAX_RETRIES, message, id);
    }

    @Override
    public long countByBuildAndStatus(long buildId, String status) {
        if (buildId <= 0 || status == null || status.isBlank()) return 0;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM kb_retrieval_projection_outbox
                WHERE index_build_id=? AND status=? AND dead_letter=FALSE
                """, Long.class, buildId, status);
        return count == null ? 0 : count;
    }

    @Override
    public boolean hasTerminalFailure(long buildId) {
        if (buildId <= 0) return false;
        Boolean failed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM kb_retrieval_projection_outbox
                    WHERE index_build_id=? AND dead_letter=TRUE)
                """, Boolean.class, buildId);
        return Boolean.TRUE.equals(failed);
    }

    private String payload(RetrievalUnit unit) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("retrievalUnitId", unit.id());
        value.put("datasetId", unit.datasetId());
        value.put("documentId", unit.documentId());
        value.put("documentVersionId", unit.documentVersionId());
        value.put("nodeId", unit.nodeId());
        value.put("indexBuildId", unit.indexBuildId());
        value.put("unitType", unit.unitType().name());
        value.put("ordinal", unit.ordinal());
        value.put("titlePath", unit.titlePath());
        value.put("content", unit.content());
        value.put("contentHash", unit.contentHash());
        value.put("tokenCount", unit.tokenCount());
        value.put("metadata", unit.metadata());
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.VALIDATION, "V2 检索单元无法序列化");
        }
    }

    private RetrievalProjectionOutboxEvent event(ResultSet rs) throws java.sql.SQLException {
        return new RetrievalProjectionOutboxEvent(rs.getLong("id"), rs.getString("event_type"),
                rs.getLong("dataset_id"), rs.getLong("document_id"), rs.getLong("document_version_id"),
                rs.getLong("index_build_id"), rs.getLong("retrieval_unit_id"), rs.getString("payload"),
                rs.getString("status"), rs.getInt("retry_count"), instant(rs, "next_retry_at"),
                instant(rs, "lease_until"), rs.getBoolean("dead_letter"), rs.getString("error_msg"),
                rs.getString("idempotency_key"));
    }

    private Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
