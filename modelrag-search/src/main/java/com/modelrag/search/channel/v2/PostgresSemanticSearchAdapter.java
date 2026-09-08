package com.modelrag.search.channel.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL V2 semantic adapter; legacy PostgresVectorStore remains untouched. */
@Service
@Profile("!test")
public class PostgresSemanticSearchAdapter implements SemanticSearchPort {
    private static final String SEARCH_SQL = """
            SELECT u.id AS retrieval_unit_id,
                   u.dataset_id,
                   u.node_id,
                   u.document_id,
                   u.document_version_id,
                   u.index_build_id,
                   u.unit_type,
                   u.title_path,
                   u.content,
                   u.metadata,
                   1 - (e.embedding <=> CAST(? AS vector)) AS score
            FROM kb_vector_embedding e
            JOIN kb_retrieval_unit u
              ON u.id=e.retrieval_unit_id
             AND u.index_build_id=e.index_build_id
            JOIN kb_document d ON d.id=u.document_id
            JOIN kb_dataset ds ON ds.id=u.dataset_id
            JOIN kb_index_build b ON b.id=u.index_build_id
            WHERE u.dataset_id=?
              AND e.embedding_profile=?
              AND d.delete_time IS NULL
              AND ds.delete_time IS NULL
              AND d.active_version_id=u.document_version_id
              AND d.active_index_build_id=u.index_build_id
              AND b.state='ACTIVE'
            ORDER BY e.embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnlyTransaction;
    private final ObjectMapper json;

    public PostgresSemanticSearchAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(jdbc, transactions, new ObjectMapper());
    }

    @Autowired
    public PostgresSemanticSearchAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = new TransactionTemplate(transactions);
        this.readOnlyTransaction.setReadOnly(true);
        this.json = json;
    }

    @Override
    public List<RetrievalCandidate> search(SemanticSearchRequest request) {
        int limit = request.limit();
        String vector = vector(request.queryEmbedding());
        List<RetrievalCandidate> result = readOnlyTransaction.execute(status -> {
            jdbc.execute("SET LOCAL hnsw.iterative_scan = 'relaxed_order'");
            return jdbc.query(SEARCH_SQL, mapper(), vector, request.datasetId(),
                    request.embeddingProfile(), vector, limit);
        });
        return result == null ? List.of() : List.copyOf(result);
    }

    private RowMapper<RetrievalCandidate> mapper() {
        return (ResultSet rs, int row) -> new RetrievalCandidate(
                rs.getLong("dataset_id"), rs.getLong("retrieval_unit_id"), rs.getLong("node_id"),
                rs.getLong("document_id"), rs.getLong("document_version_id"), rs.getLong("index_build_id"),
                RetrievalUnitType.valueOf(rs.getString("unit_type")), rs.getString("title_path"),
                rs.getString("content"), rs.getDouble("score"), RetrievalChannel.SEMANTIC, row + 1,
                metadata(rs.getString("metadata")));
    }

    private Map<String, Object> metadata(String value) {
        try {
            return json.readValue(value == null || value.isBlank() ? "{}" : value,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("检索单元元数据无法解析", error);
        }
    }

    private String vector(float[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append(',');
            result.append(values[i]);
        }
        return result.append(']').toString();
    }
}
