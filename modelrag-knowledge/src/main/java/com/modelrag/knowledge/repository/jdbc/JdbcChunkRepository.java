package com.modelrag.knowledge.repository.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.ChunkWindow;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for legacy chunk storage and bounded chunk reads. */
@Repository
@Profile("!test")
public class JdbcChunkRepository implements ChunkRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcChunkRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Legacy/offline operation. Never use this method in an online retrieval hot path. */
    @Override
    public List<Chunk> findActiveByDatasetId(long datasetId) {
        return jdbc.query("""
                SELECT c.* FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
                WHERE c.dataset_id=? AND c.delete_time IS NULL AND d.delete_time IS NULL
                  AND d.active_index_version > 0 AND c.version=d.active_index_version
                ORDER BY c.document_id,c.chunk_index
                """, (rs, n) -> chunk(rs), datasetId);
    }

    @Override
    public List<Chunk> findActiveByIds(long datasetId, Collection<Long> ids) {
        List<Long> requested = ids == null ? List.of() : ids.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(requested.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(datasetId);
        args.addAll(requested);
        return jdbc.query(activeChunkQuery("c.id IN (" + placeholders + ")"),
                (rs, n) -> chunk(rs), args.toArray());
    }

    @Override
    public List<Chunk> findActiveByParentIds(long datasetId, Collection<Long> parentIds) {
        List<Long> requested = parentIds == null ? List.of() : parentIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(requested.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(datasetId);
        args.addAll(requested);
        return jdbc.query(activeChunkQuery("c.parent_chunk_id IN (" + placeholders + ")"),
                (rs, n) -> chunk(rs), args.toArray());
    }

    @Override
    public List<Chunk> findActiveNeighbors(long datasetId, Collection<ChunkWindow> windows) {
        List<ChunkWindow> requested = windows == null ? List.of() : windows.stream()
                .filter(java.util.Objects::nonNull).toList();
        if (requested.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        args.add(datasetId);
        String predicate = requested.stream()
                .map(window -> "(c.document_id=? AND c.chunk_index BETWEEN ? AND ?)")
                .collect(java.util.stream.Collectors.joining(" OR "));
        requested.forEach(window -> {
            args.add(window.documentId());
            args.add(window.fromIndex());
            args.add(window.toIndex());
        });
        return jdbc.query(activeChunkQuery("(" + predicate + ")"),
                (rs, n) -> chunk(rs), args.toArray());
    }

    private String activeChunkQuery(String predicate) {
        return """
                SELECT c.* FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
                WHERE c.dataset_id=? AND c.delete_time IS NULL AND d.delete_time IS NULL
                  AND d.active_index_version > 0 AND c.version=d.active_index_version
                  AND %s
                ORDER BY c.document_id,c.chunk_index
                """.formatted(predicate);
    }

    @Override
    public void replaceDocumentVersion(long documentId, List<Chunk> chunks) {
        int version = chunks.stream().findFirst().map(this::chunkVersion).orElse(1);
        jdbc.update("DELETE FROM kb_chunk WHERE document_id=? AND version=?", documentId, version);
        appendChunkRows(chunks);
    }

    @Override
    public void beginDocumentVersion(long documentId, long version) {
        jdbc.update("DELETE FROM kb_chunk WHERE document_id=? AND version=?", documentId, version);
    }

    @Override
    public void append(long documentId, List<Chunk> chunks) {
        appendChunkRows(chunks);
    }

    private void appendChunkRows(List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            jdbc.update("INSERT INTO kb_chunk(id,document_id,dataset_id,chunk_index,content,metadata,parent_chunk_id,version) VALUES (?,?,?,?,CAST(? AS text),CAST(? AS jsonb),?,?)",
                    chunk.id(), chunk.documentId(), chunk.datasetId(), chunk.index(), chunk.content(), metadata(chunk.metadata()),
                    chunk.parentChunkId(), chunkVersion(chunk));
        }
    }

    @Override
    public void softDeleteByDocumentId(long documentId) {
        jdbc.update("UPDATE kb_chunk SET delete_time=NOW() WHERE document_id=? AND delete_time IS NULL", documentId);
    }

    @Override
    public void softDeleteByDatasetId(long datasetId) {
        jdbc.update("UPDATE kb_chunk SET delete_time=NOW() WHERE dataset_id=? AND delete_time IS NULL", datasetId);
    }

    @Override
    public long nextId() {
        return jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('kb_chunk','id'))", Long.class);
    }

    private Chunk chunk(ResultSet rs) throws java.sql.SQLException {
        Long parent = rs.getObject("parent_chunk_id", Long.class);
        return new Chunk(rs.getLong("id"), rs.getLong("document_id"), rs.getLong("dataset_id"), rs.getInt("chunk_index"),
                rs.getString("content"), metadata(rs.getString("metadata")), parent);
    }

    private Map<String, String> metadata(String value) {
        try {
            return json.readValue(value == null || value.isBlank() ? "{}" : value,
                    new TypeReference<Map<String, String>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("文档元数据无法解析", error);
        }
    }

    private int chunkVersion(Chunk chunk) {
        try {
            return Math.max(1, Integer.parseInt(chunk.metadata().getOrDefault("version", "1")));
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.VALIDATION, "chunk 索引版本无效");
        }
    }

    private String metadata(Map<String, String> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("文档元数据无法序列化", error);
        }
    }
}
