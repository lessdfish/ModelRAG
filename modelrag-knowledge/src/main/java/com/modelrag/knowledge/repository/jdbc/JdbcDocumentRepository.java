package com.modelrag.knowledge.repository.jdbc;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.repository.DocumentRepository;
import java.sql.ResultSet;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for logical document persistence. */
@Repository
@Profile("!test")
public class JdbcDocumentRepository implements DocumentRepository {
    private final JdbcTemplate jdbc;

    public JdbcDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Document create(long datasetId, String name, String type, String hash, String content) {
        return create(datasetId, name, type, hash, content, null, null, hash);
    }

    @Override
    public Document create(long datasetId, String name, String type, String hash, String content,
            String sourceObjectKey, String artifactObjectKey, String contentHash) {
        requireDataset(datasetId);
        if (!jdbc.query("SELECT id FROM kb_document WHERE dataset_id=? AND file_hash=? AND delete_time IS NULL",
                (rs, n) -> rs.getLong(1), datasetId, hash).isEmpty()) {
            throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT, "同一知识库中已有相同文档");
        }
        Long id;
        try {
            id = jdbc.queryForObject("INSERT INTO kb_document(dataset_id,file_name,file_type,file_hash,source_object_key,artifact_object_key,content_hash,index_state,active_index_version) VALUES (?,?,?,?,?,?,?,'BUILDING',0) RETURNING id",
                    Long.class, datasetId, name, type, hash, sourceObjectKey, artifactObjectKey, contentHash);
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT, "同一知识库中已有相同文档");
        }
        return findById(id);
    }

    @Override
    public Document findById(long id) {
        List<Document> rows = jdbc.query("SELECT * FROM kb_document WHERE id=? AND delete_time IS NULL",
                (rs, n) -> document(rs), id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        return rows.get(0);
    }

    @Override
    public void activateVersion(long documentId, long documentVersionId) {
        if (jdbc.query("SELECT id FROM kb_document WHERE id=? AND delete_time IS NULL",
                (rs, n) -> rs.getLong(1), documentId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        if (jdbc.query("SELECT id FROM kb_document_version WHERE id=? AND document_id=?",
                (rs, n) -> rs.getLong(1), documentVersionId, documentId).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "文档版本不属于该文档");
        }
        int updated = jdbc.update("""
                UPDATE kb_document SET active_version_id=?,update_time=NOW()
                WHERE id=? AND delete_time IS NULL
                AND EXISTS (SELECT 1 FROM kb_document_version v
                    WHERE v.id=? AND v.document_id=kb_document.id)
                """, documentVersionId, documentId, documentVersionId);
        if (updated != 1) throw new BusinessException(ErrorCode.INTERNAL, "文档版本激活失败");
    }

    @Override
    public void lockForIndexBuildActivation(long documentId) {
        List<Long> rows = jdbc.query("SELECT id FROM kb_document WHERE id=? AND delete_time IS NULL FOR UPDATE",
                (rs, n) -> rs.getLong(1), documentId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
    }

    @Override
    public void activateIndexBuild(long documentId, long indexBuildId) {
        if (indexBuildId <= 0) throw new BusinessException(ErrorCode.VALIDATION, "IndexBuild ID 无效");
        lockForIndexBuildActivation(documentId);
        if (jdbc.query("SELECT id FROM kb_index_build WHERE id=? AND document_id=?",
                (rs, n) -> rs.getLong(1), indexBuildId, documentId).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "IndexBuild 不属于该文档");
        }
        int updated = jdbc.update("""
                UPDATE kb_document SET active_index_build_id=?,update_time=NOW()
                WHERE id=? AND delete_time IS NULL
                AND EXISTS (SELECT 1 FROM kb_index_build b
                    WHERE b.id=? AND b.document_id=kb_document.id)
                """, indexBuildId, documentId, indexBuildId);
        if (updated != 1) throw new BusinessException(ErrorCode.INTERNAL, "IndexBuild 激活失败");
    }

    @Override
    public List<Document> findByDatasetId(long datasetId) {
        return jdbc.query("SELECT * FROM kb_document WHERE dataset_id=? AND delete_time IS NULL ORDER BY id",
                (rs, n) -> document(rs), datasetId);
    }

    @Override
    public void updateStatus(long documentId, String status, String error, int chunkCount) {
        jdbc.update("UPDATE kb_document SET index_status=?,index_state=?,error_msg=?,chunk_count=?,update_time=NOW() WHERE id=?",
                status, status, error, chunkCount, documentId);
    }

    @Override
    public void softDelete(long documentId) {
        findById(documentId);
        jdbc.update("UPDATE kb_document SET delete_time=NOW(),update_time=NOW(),index_state='DELETED' WHERE id=?", documentId);
    }

    @Override
    public void softDeleteByDatasetId(long datasetId) {
        jdbc.update("UPDATE kb_document SET delete_time=NOW(),update_time=NOW() WHERE dataset_id=? AND delete_time IS NULL", datasetId);
    }

    private void requireDataset(long datasetId) {
        if (jdbc.query("SELECT id FROM kb_dataset WHERE id=? AND delete_time IS NULL",
                (rs, n) -> rs.getLong(1), datasetId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
    }

    private Document document(ResultSet rs) throws java.sql.SQLException {
        return new Document(rs.getLong("id"), rs.getLong("dataset_id"), rs.getString("file_name"), rs.getString("file_type"),
                rs.getString("file_hash"), null, rs.getString("index_status"), rs.getString("error_msg"), rs.getInt("chunk_count"),
                rs.getString("source_object_key"), rs.getString("artifact_object_key"), rs.getString("content_hash"),
                rs.getObject("active_version_id", Long.class),
                rs.getObject("active_index_build_id", Long.class));
    }
}
