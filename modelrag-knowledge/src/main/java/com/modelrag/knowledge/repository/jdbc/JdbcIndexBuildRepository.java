package com.modelrag.knowledge.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.model.ActiveBuildRef;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL adapter for V2 index-build lifecycle and bounded build history. */
@Repository
@Profile("!test")
public class JdbcIndexBuildRepository implements IndexBuildRepository {
    private static final int MAX_QUERY_LIMIT = 500;
    private static final int MAX_ACTIVE_SCOPE_LIMIT = 10_001;
    private static final String BUILD_COLUMNS = "id,dataset_id,document_id,document_version_id,build_no,state,"
            + "embedding_profile,rerank_profile,node_count,unit_count,vector_count,lexical_count,error_msg,metadata,"
            + "create_time,start_time,ready_time,active_time,failed_time";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionOperations transactions;

    public JdbcIndexBuildRepository(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper(), null);
    }

    public JdbcIndexBuildRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, null);
    }

    @Autowired
    public JdbcIndexBuildRepository(JdbcTemplate jdbc, ObjectMapper json, TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
    }

    @Override
    public IndexBuild create(long datasetId, long documentId, long documentVersionId,
            String embeddingProfile, String rerankProfile, Map<String, Object> metadata) {
        validateCreate(datasetId, documentId, documentVersionId, embeddingProfile);
        if (transactions == null) {
            return createWithinTransaction(datasetId, documentId, documentVersionId,
                    embeddingProfile, rerankProfile, metadata);
        }
        return transactions.execute(status -> createWithinTransaction(datasetId, documentId, documentVersionId,
                embeddingProfile, rerankProfile, metadata));
    }

    @Override
    public Optional<IndexBuild> findById(long buildId) {
        if (buildId <= 0) return Optional.empty();
        return query("SELECT " + BUILD_COLUMNS + " FROM kb_index_build WHERE id=?", buildId)
                .stream().findFirst();
    }

    @Override
    public Optional<IndexBuild> findActiveByDocumentId(long documentId) {
        if (documentId <= 0) return Optional.empty();
        return query("""
                SELECT b.id,b.dataset_id,b.document_id,b.document_version_id,b.build_no,b.state,
                       b.embedding_profile,b.rerank_profile,b.node_count,b.unit_count,b.vector_count,
                       b.lexical_count,b.error_msg,b.metadata,b.create_time,b.start_time,b.ready_time,
                       b.active_time,b.failed_time
                FROM kb_index_build b
                JOIN kb_document d ON d.active_index_build_id=b.id AND b.document_id=d.id
                WHERE d.id=? AND b.state='ACTIVE' AND d.delete_time IS NULL
                """, documentId).stream().findFirst();
    }

    @Override
    public List<IndexBuild> findByDocumentId(long documentId, int offset, int limit) {
        if (documentId <= 0) return List.of();
        int boundedLimit = boundedLimit(limit);
        if (boundedLimit == 0) return List.of();
        return query("SELECT " + BUILD_COLUMNS + " FROM kb_index_build "
                + "WHERE document_id=? ORDER BY build_no DESC LIMIT ? OFFSET ?",
                documentId, boundedLimit, Math.max(0, offset));
    }

    @Override
    public List<ActiveBuildRef> findActiveByDataset(long datasetId, int limit) {
        if (datasetId <= 0) return List.of();
        int boundedLimit = Math.min(MAX_ACTIVE_SCOPE_LIMIT, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        return jdbc.query("""
                SELECT d.id AS document_id,
                       d.active_version_id AS document_version_id,
                       d.active_index_build_id AS index_build_id,
                       b.embedding_profile
                FROM kb_document d
                JOIN kb_dataset ds ON ds.id=d.dataset_id
                JOIN kb_index_build b ON b.id=d.active_index_build_id
                    AND b.document_id=d.id
                WHERE d.dataset_id=?
                  AND d.delete_time IS NULL
                  AND ds.delete_time IS NULL
                  AND d.active_version_id=b.document_version_id
                  AND b.state='ACTIVE'
                ORDER BY d.id
                LIMIT ?
                """, (rs, row) -> new ActiveBuildRef(rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getLong("index_build_id"),
                rs.getString("embedding_profile")), datasetId, boundedLimit);
    }

    @Override
    public List<IndexBuild> findByState(IndexBuildState state, int limit) {
        if (state == null) return List.of();
        int boundedLimit = boundedLimit(limit);
        if (boundedLimit == 0) return List.of();
        return query("SELECT " + BUILD_COLUMNS + " FROM kb_index_build "
                + "WHERE state=? ORDER BY id LIMIT ?", state.name(), boundedLimit);
    }

    @Override
    public boolean transition(long buildId, IndexBuildState expected, IndexBuildState next) {
        if (buildId <= 0 || expected == null || next == null || !expected.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.VALIDATION, "非法的 IndexBuild 状态转移");
        }
        int updated = jdbc.update("""
                UPDATE kb_index_build
                SET state=?,
                    start_time=CASE WHEN ?='PARSING' AND start_time IS NULL THEN NOW() ELSE start_time END,
                    ready_time=CASE WHEN ?='READY' THEN COALESCE(ready_time,NOW()) ELSE ready_time END,
                    active_time=CASE WHEN ?='ACTIVE' THEN NOW() ELSE active_time END,
                    failed_time=CASE WHEN ?='FAILED' THEN NOW() ELSE failed_time END
                WHERE id=? AND state=?
                """, next.name(), next.name(), next.name(), next.name(), next.name(), buildId, expected.name());
        return updated == 1;
    }

    @Override
    public void updateCounts(long buildId, long nodeCount, long unitCount, long vectorCount, long lexicalCount) {
        if (buildId <= 0 || nodeCount < 0 || unitCount < 0 || vectorCount < 0 || lexicalCount < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "IndexBuild 计数无效");
        }
        int updated = jdbc.update("""
                UPDATE kb_index_build
                SET node_count=?,unit_count=?,vector_count=?,lexical_count=?
                WHERE id=?
                """, nodeCount, unitCount, vectorCount, lexicalCount, buildId);
        if (updated != 1) throw new BusinessException(ErrorCode.NOT_FOUND, "IndexBuild 不存在");
    }

    @Override
    public void markFailed(long buildId, String safeError) {
        if (buildId <= 0) throw new BusinessException(ErrorCode.VALIDATION, "IndexBuild ID 无效");
        String message = safeError == null ? "" : safeError.substring(0, Math.min(2000, safeError.length()));
        int updated = jdbc.update("""
                UPDATE kb_index_build
                SET state='FAILED',error_msg=?,failed_time=NOW()
                WHERE id=? AND state NOT IN ('ACTIVE','SUPERSEDED')
                """, message, buildId);
        if (updated == 1) return;
        IndexBuild existing = findById(buildId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "IndexBuild 不存在"));
        throw new BusinessException(ErrorCode.VALIDATION,
                "不能将 " + existing.state().name() + " 构建标记为 FAILED");
    }

    private IndexBuild createWithinTransaction(long datasetId, long documentId, long documentVersionId,
            String embeddingProfile, String rerankProfile, Map<String, Object> metadata) {
        List<DocumentRow> documents = jdbc.query("""
                SELECT dataset_id,active_version_id
                FROM kb_document
                WHERE id=? AND dataset_id=? AND delete_time IS NULL
                FOR UPDATE
                """, (rs, row) -> new DocumentRow(rs.getLong("dataset_id"),
                rs.getObject("active_version_id", Long.class)), documentId, datasetId);
        if (documents.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        DocumentRow document = documents.get(0);
        if (document.activeVersionId == null || document.activeVersionId != documentVersionId) {
            throw new BusinessException(ErrorCode.VALIDATION, "IndexBuild 必须针对当前活动文档版本");
        }
        if (jdbc.query("SELECT id FROM kb_document_version WHERE id=? AND document_id=?",
                (rs, row) -> rs.getLong(1), documentVersionId, documentId).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档版本不属于该文档");
        }
        Long buildNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(build_no),0)+1 FROM kb_index_build WHERE document_id=?",
                Long.class, documentId);
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO kb_index_build(
                        dataset_id,document_id,document_version_id,build_no,state,embedding_profile,
                        rerank_profile,metadata)
                    VALUES (?,?,?,?,'CREATED',?,?,CAST(? AS jsonb))
                    RETURNING id
                    """, Long.class, datasetId, documentId, documentVersionId, buildNo,
                    embeddingProfile, rerankProfile, metadata(metadata));
            return findById(id).orElseThrow(
                    () -> new BusinessException(ErrorCode.INTERNAL, "IndexBuild 创建后不可见"));
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "IndexBuild 编号冲突，请重试");
        }
    }

    private void validateCreate(long datasetId, long documentId, long documentVersionId, String embeddingProfile) {
        if (datasetId <= 0 || documentId <= 0 || documentVersionId <= 0
                || embeddingProfile == null || embeddingProfile.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "IndexBuild 参数无效");
        }
    }

    private int boundedLimit(int limit) {
        return Math.min(MAX_QUERY_LIMIT, Math.max(0, limit));
    }

    private List<IndexBuild> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> build(rs), args);
    }

    private IndexBuild build(ResultSet rs) throws java.sql.SQLException {
        return new IndexBuild(rs.getLong("id"), rs.getLong("dataset_id"), rs.getLong("document_id"),
                rs.getLong("document_version_id"), rs.getLong("build_no"),
                IndexBuildState.valueOf(rs.getString("state")), rs.getString("embedding_profile"),
                rs.getString("rerank_profile"), rs.getLong("node_count"), rs.getLong("unit_count"),
                rs.getLong("vector_count"), rs.getLong("lexical_count"), rs.getString("error_msg"),
                metadata(rs.getString("metadata")), instant(rs, "create_time"), instant(rs, "start_time"),
                instant(rs, "ready_time"), instant(rs, "active_time"), instant(rs, "failed_time"));
    }

    private java.time.Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String metadata(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.VALIDATION, "IndexBuild 元数据无法序列化");
        }
    }

    private Map<String, Object> metadata(String value) {
        try {
            return json.readValue(value == null || value.isBlank() ? "{}" : value,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("IndexBuild 元数据无法解析", error);
        }
    }

    private record DocumentRow(long datasetId, Long activeVersionId) { }
}
