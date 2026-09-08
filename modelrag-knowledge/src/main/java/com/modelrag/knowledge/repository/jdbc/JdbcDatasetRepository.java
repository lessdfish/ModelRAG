package com.modelrag.knowledge.repository.jdbc;

import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.repository.DatasetRepository;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for dataset persistence and routing. */
@Repository
@Profile("!test")
public class JdbcDatasetRepository implements DatasetRepository {
    private final JdbcTemplate jdbc;
    private final TextEmbeddingProvider embeddings;

    public JdbcDatasetRepository(JdbcTemplate jdbc, TextEmbeddingProvider embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @Override
    public Dataset create(String name, String description, Integer chunkSize, Integer chunkOverlap) {
        return create(name, description, chunkSize, chunkOverlap, null, null);
    }

    @Override
    public Dataset create(String name, String description, Integer size, Integer overlap, Integer topK,
            Double threshold) {
        requireName(name);
        int normalizedSize = chunkSize(size);
        int normalizedOverlap = chunkOverlap(overlap, normalizedSize);
        Long id = jdbc.queryForObject(
                "INSERT INTO kb_dataset(name,description,chunk_size,chunk_overlap,top_k,similarity_threshold,embedding_model,embedding_dimensions,embedding_profile_version,routing_embedding) VALUES (?,?,?,?,?,?,?, ?, ?,CAST(? AS vector)) RETURNING id",
                Long.class, name.trim(), description, normalizedSize, normalizedOverlap, topK(topK), threshold(threshold),
                "Qwen3-Embedding-0.6B", 1024, "qwen3-embedding-0.6b-1024-v1",
                vector(embeddings.embed(0, routingText(name, description))));
        return findById(id);
    }

    @Override
    public List<Dataset> findAll() {
        return jdbc.query("SELECT * FROM kb_dataset WHERE delete_time IS NULL ORDER BY id", (rs, n) -> dataset(rs));
    }

    @Override
    public List<Dataset> route(String query, Set<Long> allowedDatasetIds, int limit) {
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
    public Set<Long> findIndexedDatasetIds() {
        return jdbc.queryForList("""
                SELECT DISTINCT dataset_id FROM kb_document
                WHERE delete_time IS NULL AND active_index_version>0
                """, Long.class).stream().collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Dataset findById(long id) {
        List<Dataset> rows = jdbc.query("SELECT * FROM kb_dataset WHERE id=? AND delete_time IS NULL",
                (rs, n) -> dataset(rs), id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        return rows.get(0);
    }

    @Override
    public Dataset update(long id, String name, String description) {
        Dataset current = findById(id);
        return update(id, name, description, current.chunkSize(), current.chunkOverlap(), current.topK(),
                current.threshold());
    }

    @Override
    public Dataset update(long id, String name, String description, Integer size, Integer overlap, Integer topK,
            Double threshold) {
        findById(id);
        requireName(name);
        int normalizedSize = chunkSize(size);
        int normalizedOverlap = chunkOverlap(overlap, normalizedSize);
        jdbc.update("UPDATE kb_dataset SET name=?,description=?,chunk_size=?,chunk_overlap=?,top_k=?,similarity_threshold=?,routing_embedding=CAST(? AS vector),revision=revision+1,update_time=NOW() WHERE id=?",
                name.trim(), description, normalizedSize, normalizedOverlap, topK(topK), threshold(threshold),
                vector(embeddings.embed(0, routingText(name, description))), id);
        return findById(id);
    }

    @Override
    public void softDelete(long id) {
        findById(id);
        jdbc.update("UPDATE kb_dataset SET delete_time=NOW(),update_time=NOW() WHERE id=?", id);
    }

    @Override
    public Dataset bumpRevision(long datasetId) {
        jdbc.update("UPDATE kb_dataset SET revision=revision+1,update_time=NOW() WHERE id=? AND delete_time IS NULL", datasetId);
        return findById(datasetId);
    }

    private Dataset dataset(ResultSet rs) throws java.sql.SQLException {
        return new Dataset(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getInt("chunk_size"),
                rs.getInt("chunk_overlap"), rs.getInt("top_k"), rs.getDouble("similarity_threshold"), rs.getLong("revision"));
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
