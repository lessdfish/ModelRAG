package com.modelrag.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

/** PostgreSQL and pgvector are the sole production business-data implementation. */
@Service
@Profile("!test")
public class PostgresKnowledgeStore implements KnowledgeStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TextEmbeddingProvider embeddings;

    public PostgresKnowledgeStore(JdbcTemplate jdbc, TextEmbeddingProvider embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @Override
    public Dataset createDataset(String name, String description, Integer chunkSize, Integer chunkOverlap) {
        return createDataset(name, description, chunkSize, chunkOverlap, null, null);
    }

    @Override
    public Dataset createDataset(String name, String description, Integer size, Integer overlap, Integer topK,
            Double threshold) {
        requireName(name);
        int normalizedSize = chunkSize(size);
        int normalizedOverlap = chunkOverlap(overlap, normalizedSize);
        Long id = jdbc.queryForObject(
                "INSERT INTO kb_dataset(name,description,chunk_size,chunk_overlap,top_k,similarity_threshold,embedding_model,embedding_dimensions,embedding_profile_version,routing_embedding) VALUES (?,?,?,?,?,?,?, ?, ?,CAST(? AS vector)) RETURNING id",
                Long.class, name.trim(), description, normalizedSize, normalizedOverlap, topK(topK), threshold(threshold),
                "Qwen3-Embedding-0.6B", 1024, "qwen3-embedding-0.6b-1024-v1",
                vector(embeddings.embed(0, routingText(name, description))));
        return dataset(id);
    }

    @Override
    public List<Dataset> datasets() {
        return jdbc.query("SELECT * FROM kb_dataset WHERE delete_time IS NULL ORDER BY id", (rs, n) -> dataset(rs));
    }

    @Override
    public List<Dataset> routeDatasets(String query, Set<Long> allowedDatasetIds, int limit) {
        if (query == null || query.isBlank()) return List.of();
        StringBuilder sql = new StringBuilder("""
                SELECT d.*, CASE WHEN d.routing_embedding IS NULL THEN 1.0
                    ELSE d.routing_embedding <=> CAST(? AS vector) END AS routing_distance
                FROM kb_dataset d
                WHERE d.delete_time IS NULL
                  AND EXISTS (SELECT 1 FROM kb_document doc WHERE doc.dataset_id=d.id
                      AND doc.delete_time IS NULL AND doc.active_index_version>0)
                """);
        List<Object> args = new ArrayList<>();
        args.add(vector(embeddings.embed(0, query)));
        if (allowedDatasetIds != null && !allowedDatasetIds.isEmpty()) {
            sql.append(" AND d.id IN (");
            sql.append(String.join(",", java.util.Collections.nCopies(allowedDatasetIds.size(), "?")));
            sql.append(')');
            args.addAll(allowedDatasetIds.stream().sorted().toList());
        }
        List<RoutingRow> rows = jdbc.query(sql.toString(), (rs, n) -> new RoutingRow(dataset(rs),
                rs.getDouble("routing_distance")), args.toArray());
        return rows.stream()
                .sorted(java.util.Comparator.comparingDouble((RoutingRow row) -> routeScore(row, query)).reversed()
                        .thenComparing(row -> row.dataset().id()))
                .limit(Math.max(1, Math.min(3, limit))).map(RoutingRow::dataset).toList();
    }

    @Override
    public Set<Long> indexedDatasetIds() {
        return jdbc.queryForList("""
                SELECT DISTINCT dataset_id FROM kb_document
                WHERE delete_time IS NULL AND active_index_version>0
                """, Long.class).stream().collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Dataset dataset(long id) {
        List<Dataset> rows = jdbc.query("SELECT * FROM kb_dataset WHERE id=? AND delete_time IS NULL",
                (rs, n) -> dataset(rs), id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        return rows.get(0);
    }

    @Override
    public Dataset updateDataset(long id, String name, String description) {
        Dataset current = dataset(id);
        return updateDataset(id, name, description, current.chunkSize(), current.chunkOverlap(), current.topK(),
                current.threshold());
    }

    @Override
    public Dataset updateDataset(long id, String name, String description, Integer size, Integer overlap, Integer topK,
            Double threshold) {
        dataset(id);
        requireName(name);
        int normalizedSize = chunkSize(size);
        int normalizedOverlap = chunkOverlap(overlap, normalizedSize);
        jdbc.update("UPDATE kb_dataset SET name=?,description=?,chunk_size=?,chunk_overlap=?,top_k=?,similarity_threshold=?,routing_embedding=CAST(? AS vector),revision=revision+1,update_time=NOW() WHERE id=?",
                name.trim(), description, normalizedSize, normalizedOverlap, topK(topK), threshold(threshold),
                vector(embeddings.embed(0, routingText(name, description))), id);
        return dataset(id);
    }

    @Override
    public void deleteDataset(long id) {
        dataset(id);
        jdbc.update("UPDATE kb_dataset SET delete_time=NOW(),update_time=NOW() WHERE id=?", id);
        jdbc.update("UPDATE kb_document SET delete_time=NOW(),update_time=NOW() WHERE dataset_id=? AND delete_time IS NULL", id);
        jdbc.update("UPDATE kb_chunk SET delete_time=NOW() WHERE dataset_id=? AND delete_time IS NULL", id);
    }

    @Override
    public Document addDocument(long datasetId, String name, String type, String hash, String content) {
        return addDocument(datasetId, name, type, hash, content, null, null, hash);
    }

    @Override
    public Document addDocument(long datasetId, String name, String type, String hash, String content,
            String sourceObjectKey, String artifactObjectKey, String contentHash) {
        dataset(datasetId);
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
        bumpDatasetRevision(datasetId);
        return document(id);
    }

    @Override
    public Document document(long id) {
        List<Document> rows = jdbc.query("SELECT * FROM kb_document WHERE id=? AND delete_time IS NULL",
                (rs, n) -> document(rs), id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        return rows.get(0);
    }

    @Override
    public void deleteDocument(long datasetId, long documentId) {
        Document document = document(documentId);
        if (document.datasetId() != datasetId) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        jdbc.update("UPDATE kb_document SET delete_time=NOW(),update_time=NOW(),index_state='DELETED' WHERE id=?", documentId);
        jdbc.update("UPDATE kb_chunk SET delete_time=NOW() WHERE document_id=? AND delete_time IS NULL", documentId);
        bumpDatasetRevision(datasetId);
    }

    @Override
    public void status(long id, String status, String error, int count) {
        jdbc.update("UPDATE kb_document SET index_status=?,index_state=?,error_msg=?,chunk_count=?,update_time=NOW() WHERE id=?",
                status, status, error, count, id);
    }

    @Override
    public long beginIndexVersion(long documentId) {
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
    public void activateIndexVersion(long documentId, long version) {
        jdbc.update("""
                UPDATE kb_document SET active_index_version=?,index_status='READY',index_state='READY',
                error_msg=NULL,update_time=NOW() WHERE id=? AND version=? AND delete_time IS NULL
                """, version, documentId, version);
    }

    @Override
    public Map<Long, Long> activeIndexVersions(long datasetId) {
        return jdbc.query("""
                SELECT id,active_index_version FROM kb_document
                WHERE dataset_id=? AND delete_time IS NULL AND active_index_version > 0
                """, (rs, n) -> Map.entry(rs.getLong(1), rs.getLong(2)), datasetId)
                .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Map<Long, Long> allActiveIndexVersions() {
        return jdbc.query("""
                SELECT id,active_index_version FROM kb_document
                WHERE delete_time IS NULL AND active_index_version > 0
                """, (rs, n) -> Map.entry(rs.getLong(1), rs.getLong(2)))
                .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public List<Document> documents(long datasetId) {
        return jdbc.query("SELECT * FROM kb_document WHERE dataset_id=? AND delete_time IS NULL ORDER BY id",
                (rs, n) -> document(rs), datasetId);
    }

    @Override
    public List<Chunk> chunks(long datasetId) {
        return jdbc.query("""
                SELECT c.* FROM kb_chunk c JOIN kb_document d ON d.id=c.document_id
                WHERE c.dataset_id=? AND c.delete_time IS NULL AND d.delete_time IS NULL
                  AND d.active_index_version > 0 AND c.version=d.active_index_version
                ORDER BY c.document_id,c.chunk_index
                """, (rs, n) -> chunk(rs), datasetId);
    }

    @Override
    public void chunks(long documentId, List<Chunk> chunks) {
        int version = chunks.stream().findFirst().map(this::chunkVersion).orElse(1);
        jdbc.update("DELETE FROM kb_chunk WHERE document_id=? AND version=?", documentId, version);
        appendChunkRows(chunks);
        bumpDatasetRevision(document(documentId).datasetId());
    }

    @Override
    public void beginChunks(long documentId, long version) {
        jdbc.update("DELETE FROM kb_chunk WHERE document_id=? AND version=?", documentId, version);
    }

    @Override
    public void appendChunks(long documentId, List<Chunk> chunks) {
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
    public long nextId() {
        return jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('kb_chunk','id'))", Long.class);
    }

    @Override
    public Dataset bumpDatasetRevision(long datasetId) {
        jdbc.update("UPDATE kb_dataset SET revision=revision+1,update_time=NOW() WHERE id=? AND delete_time IS NULL", datasetId);
        return dataset(datasetId);
    }

    private Dataset dataset(ResultSet rs) throws java.sql.SQLException {
        return new Dataset(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getInt("chunk_size"),
                rs.getInt("chunk_overlap"), rs.getInt("top_k"), rs.getDouble("similarity_threshold"), rs.getLong("revision"));
    }

    private Document document(ResultSet rs) throws java.sql.SQLException {
        return new Document(rs.getLong("id"), rs.getLong("dataset_id"), rs.getString("file_name"), rs.getString("file_type"),
                rs.getString("file_hash"), null, rs.getString("index_status"), rs.getString("error_msg"), rs.getInt("chunk_count"),
                rs.getString("source_object_key"), rs.getString("artifact_object_key"), rs.getString("content_hash"));
    }

    private Chunk chunk(ResultSet rs) throws java.sql.SQLException {
        Long parent = rs.getObject("parent_chunk_id", Long.class);
        return new Chunk(rs.getLong("id"), rs.getLong("document_id"), rs.getLong("dataset_id"), rs.getInt("chunk_index"),
                rs.getString("content"), metadata(rs.getString("metadata")), parent);
    }

    private Map<String, String> metadata(String value) {
        try { return JSON.readValue(value == null || value.isBlank() ? "{}" : value,
                new TypeReference<Map<String, String>>() {}); }
        catch (Exception error) { throw new IllegalStateException("文档元数据无法解析", error); }
    }

    private int chunkVersion(Chunk chunk) {
        try {
            return Math.max(1, Integer.parseInt(chunk.metadata().getOrDefault("version", "1")));
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.VALIDATION, "chunk 索引版本无效");
        }
    }

    private String metadata(Map<String, String> value) {
        try { return JSON.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw new IllegalArgumentException("文档元数据无法序列化", e); }
    }

    private record RoutingRow(Dataset dataset, double distance) { }
    private double routeScore(RoutingRow row, String query) {
        return (1 - Math.max(0, Math.min(2, row.distance()))) * 10 + metadataRelevance(row.dataset(), query);
    }
    private int metadataRelevance(Dataset dataset, String query) {
        String source = query.replaceAll("[\\s，。！？、：:]+", "");
        String target = routingText(dataset.name(), dataset.description());
        int score = 0;
        for (int index = 0; index + 1 < source.length(); index++) {
            if (target.contains(source.substring(index, index + 2))) score++;
        }
        return score;
    }
    private String routingText(String name, String description) {
        return (name == null ? "" : name.trim()) + "\n" + (description == null ? "" : description.trim());
    }
    private String vector(float[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) result.append(',');
            result.append(values[index]);
        }
        return result.append(']').toString();
    }

    private void requireName(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.VALIDATION, "知识库名称不能为空");
    }

    private int chunkSize(Integer value) {
        int next = value == null ? 600 : value;
        if (next < 128 || next > 4096) throw new BusinessException(ErrorCode.VALIDATION, "chunkSize 必须在 128 到 4096 之间");
        return next;
    }

    private int chunkOverlap(Integer value, int size) {
        int next = value == null ? 80 : value;
        if (next < 0 || next >= size || next > size / 4) throw new BusinessException(ErrorCode.VALIDATION, "chunkOverlap 不得超过 chunkSize 的 25%");
        return next;
    }

    private int topK(Integer value) {
        int next = value == null ? 5 : value;
        if (next < 1 || next > 20) throw new BusinessException(ErrorCode.VALIDATION, "topK 必须在 1 到 20 之间");
        return next;
    }

    private double threshold(Double value) {
        double next = value == null ? .7 : value;
        if (next < 0 || next > 1) throw new BusinessException(ErrorCode.VALIDATION, "threshold 必须在 0 到 1 之间");
        return next;
    }
}
