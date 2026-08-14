package com.modelrag.indexing.outbox;

import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.outbox.IndexOutboxEvent;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persisted transactional outbox. Consumers may safely resume after an application restart. */
@Service
@Profile("!test")
public class PostgresIndexOutbox implements IndexOutbox {
    private final JdbcTemplate jdbc;
    public PostgresIndexOutbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public IndexOutboxEvent append(String type,long dataset,long document,long chunk,String payload) {
        String idempotency = type + ":" + dataset + ":" + document + ":" + chunk + ":" + Integer.toHexString(payload == null ? 0 : payload.hashCode());
        Long id=jdbc.queryForObject("""
                INSERT INTO kb_index_outbox(event_type,dataset_id,document_id,chunk_id,payload,status,next_retry_at,
                idempotency_key,index_version)
                VALUES (?,?,?,?,CAST(? AS jsonb),'PENDING',NOW(),?,COALESCE((CAST(? AS jsonb)->>'version')::bigint,1))
                ON CONFLICT(idempotency_key) WHERE idempotency_key IS NOT NULL
                DO UPDATE SET update_time=NOW() RETURNING id
                """,Long.class,type,dataset,document,chunk,payload,idempotency,payload);
        return event(id);
    }
    @Override public List<IndexOutboxEvent> due() {
        return jdbc.query("""
                WITH claimed AS (
                    SELECT id FROM kb_index_outbox
                    WHERE dead_letter=FALSE AND ((status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=NOW()))
                       OR (status='PROCESSING' AND lease_until<NOW()))
                    ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 100
                )
                UPDATE kb_index_outbox o SET status='PROCESSING',lease_until=NOW()+INTERVAL '60 seconds',update_time=NOW()
                FROM claimed c WHERE o.id=c.id
                RETURNING o.*
                """, (rs,n)->map(rs));
    }
    @Override public void save(IndexOutboxEvent event) { jdbc.update("UPDATE kb_index_outbox SET status=?,retry_count=?,next_retry_at=?,error_msg=?,dead_letter=(?='FAILED'),update_time=NOW() WHERE id=?",event.status(),event.retryCount(),event.nextRetryAt()==null?null:java.sql.Timestamp.from(event.nextRetryAt()),event.error(),event.status(),event.id()); }
    @Override public int requeueDataset(long datasetId) { return jdbc.update("UPDATE kb_index_outbox SET status='PENDING',retry_count=0,next_retry_at=NOW(),error_msg=NULL,dead_letter=FALSE,update_time=NOW() WHERE dataset_id=?",datasetId); }
    public boolean isVersionComplete(long documentId, long version) {
        Boolean complete = jdbc.queryForObject("""
                SELECT NOT EXISTS (SELECT 1 FROM kb_index_outbox
                    WHERE document_id=? AND index_version=?
                      AND status IN ('PENDING','PROCESSING','FAILED'))
                """, Boolean.class, documentId, version);
        return Boolean.TRUE.equals(complete);
    }

    public void markReadyIfComplete(long documentId) {
        jdbc.update("""
                UPDATE kb_document SET active_index_version=version,
                index_status='READY',index_state='READY',update_time=NOW()
                WHERE id=? AND index_status='SEARCH_SYNCING'
                  AND NOT EXISTS (SELECT 1 FROM kb_index_outbox
                    WHERE document_id=? AND index_version=kb_document.version
                      AND status IN ('PENDING','PROCESSING','FAILED'))
                """, documentId, documentId);
    }
    private IndexOutboxEvent event(long id) { return jdbc.queryForObject("SELECT * FROM kb_index_outbox WHERE id=?",(rs,n)->map(rs),id); }
    private IndexOutboxEvent map(ResultSet rs) throws java.sql.SQLException { java.sql.Timestamp retry=rs.getTimestamp("next_retry_at"); return new IndexOutboxEvent(rs.getLong("id"),rs.getString("event_type"),rs.getLong("dataset_id"),rs.getLong("document_id"),rs.getLong("chunk_id"),rs.getString("payload"),rs.getString("status"),rs.getInt("retry_count"),retry==null?Instant.EPOCH:retry.toInstant(),rs.getString("error_msg")); }
}
