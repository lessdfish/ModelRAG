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
@Profile("postgres")
public class PostgresIndexOutbox implements IndexOutbox {
    private final JdbcTemplate jdbc;
    public PostgresIndexOutbox(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public IndexOutboxEvent append(String type,long dataset,long document,long chunk,String payload) {
        Long id=jdbc.queryForObject("INSERT INTO kb_index_outbox(event_type,dataset_id,document_id,chunk_id,payload,status,next_retry_at) VALUES (?,?,?,?,CAST(? AS jsonb),'PENDING',NOW()) RETURNING id",Long.class,type,dataset,document,chunk,payload);
        return event(id);
    }
    @Override public List<IndexOutboxEvent> due() { return jdbc.query("SELECT * FROM kb_index_outbox WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=NOW()) ORDER BY id LIMIT 100",(rs,n)->map(rs)); }
    @Override public void save(IndexOutboxEvent event) { jdbc.update("UPDATE kb_index_outbox SET status=?,retry_count=?,next_retry_at=?,error_msg=?,update_time=NOW() WHERE id=?",event.status(),event.retryCount(),event.nextRetryAt()==null?null:java.sql.Timestamp.from(event.nextRetryAt()),event.error(),event.id()); }
    @Override public int requeueDataset(long datasetId) { return jdbc.update("UPDATE kb_index_outbox SET status='PENDING',retry_count=0,next_retry_at=NOW(),error_msg=NULL,update_time=NOW() WHERE dataset_id=?",datasetId); }
    private IndexOutboxEvent event(long id) { return jdbc.queryForObject("SELECT * FROM kb_index_outbox WHERE id=?",(rs,n)->map(rs),id); }
    private IndexOutboxEvent map(ResultSet rs) throws java.sql.SQLException { java.sql.Timestamp retry=rs.getTimestamp("next_retry_at"); return new IndexOutboxEvent(rs.getLong("id"),rs.getString("event_type"),rs.getLong("dataset_id"),rs.getLong("document_id"),rs.getLong("chunk_id"),rs.getString("payload"),rs.getString("status"),rs.getInt("retry_count"),retry==null?Instant.EPOCH:retry.toInstant(),rs.getString("error_msg")); }
}
