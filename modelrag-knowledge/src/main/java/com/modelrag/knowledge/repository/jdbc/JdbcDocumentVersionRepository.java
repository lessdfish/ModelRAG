package com.modelrag.knowledge.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.DocumentParseStatus;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.repository.DocumentVersionRepository;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL adapter for immutable document content versions. */
@Repository
@Profile("!test")
public class JdbcDocumentVersionRepository implements DocumentVersionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionOperations transactions;

    public JdbcDocumentVersionRepository(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper(), null);
    }

    public JdbcDocumentVersionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, null);
    }

    @Autowired
    public JdbcDocumentVersionRepository(JdbcTemplate jdbc, ObjectMapper json, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
    }

    @Override
    public DocumentVersion create(long documentId, String sourceHash, String sourceObjectKey,
            String artifactObjectKey, String contentHash, String parserName, String parserVersion,
            DocumentParseStatus parseStatus, Map<String, Object> metadata) {
        if (parseStatus == null) throw new BusinessException(ErrorCode.VALIDATION, "解析状态不能为空");
        if (transactions == null) return createWithinTransaction(documentId, sourceHash, sourceObjectKey,
                artifactObjectKey, contentHash, parserName, parserVersion, parseStatus, metadata);
        return transactions.execute(status -> createWithinTransaction(documentId, sourceHash, sourceObjectKey,
                artifactObjectKey, contentHash, parserName, parserVersion, parseStatus, metadata));
    }

    private DocumentVersion createWithinTransaction(long documentId, String sourceHash, String sourceObjectKey,
            String artifactObjectKey, String contentHash, String parserName, String parserVersion,
            DocumentParseStatus parseStatus, Map<String, Object> metadata) {
        if (jdbc.query("SELECT id FROM kb_document WHERE id=? AND delete_time IS NULL",
                (rs, n) -> rs.getLong(1), documentId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        // The document row lock serializes max(version_no)+1 for all callers that share the
        // lifecycle transaction. The unique key remains the database guard.
        jdbc.queryForObject("SELECT id FROM kb_document WHERE id=? AND delete_time IS NULL FOR UPDATE",
                Long.class, documentId);
        Long nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM kb_document_version WHERE document_id=?",
                Long.class, documentId);
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO kb_document_version(document_id,version_no,source_hash,source_object_key,
                        artifact_object_key,content_hash,parser_name,parser_version,parse_status,metadata,ready_time)
                    VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CASE WHEN ?='READY' THEN NOW() ELSE NULL END)
                    RETURNING id
                    """, Long.class, documentId, nextVersion, sourceHash, sourceObjectKey, artifactObjectKey,
                    contentHash, parserName, parserVersion, parseStatus.name(), metadata(metadata), parseStatus.name());
            return findById(id).orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL, "文档版本创建后不可见"));
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "文档版本号冲突，请重试");
        }
    }

    @Override
    public Optional<DocumentVersion> findById(long id) {
        return query("SELECT * FROM kb_document_version WHERE id=?", id).stream().findFirst();
    }

    @Override
    public Optional<DocumentVersion> findActiveByDocumentId(long documentId) {
        return query("""
                SELECT v.* FROM kb_document_version v
                JOIN kb_document d ON d.active_version_id=v.id AND d.id=v.document_id
                WHERE d.id=? AND d.delete_time IS NULL
                """, documentId).stream().findFirst();
    }

    @Override
    public List<DocumentVersion> findByDocumentId(long documentId) {
        return query("SELECT * FROM kb_document_version WHERE document_id=? ORDER BY version_no", documentId);
    }

    private List<DocumentVersion> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> version(rs), args);
    }

    private DocumentVersion version(ResultSet rs) throws java.sql.SQLException {
        Timestamp createTime = rs.getTimestamp("create_time");
        Timestamp readyTime = rs.getTimestamp("ready_time");
        return new DocumentVersion(rs.getLong("id"), rs.getLong("document_id"), rs.getLong("version_no"),
                rs.getString("source_hash"), rs.getString("source_object_key"), rs.getString("artifact_object_key"),
                rs.getString("content_hash"), rs.getString("parser_name"), rs.getString("parser_version"),
                DocumentParseStatus.valueOf(rs.getString("parse_status")), metadata(rs.getString("metadata")),
                createTime == null ? null : createTime.toInstant(), readyTime == null ? null : readyTime.toInstant());
    }

    private String metadata(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档版本元数据无法序列化");
        }
    }

    private Map<String, Object> metadata(String value) {
        try {
            return json.readValue(value == null || value.isBlank() ? "{}" : value,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("文档版本元数据无法解析", error);
        }
    }
}
