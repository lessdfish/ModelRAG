package com.modelrag.search.channel.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL V2 semantic adapter; legacy PostgresVectorStore remains untouched. */
@Service
@Profile("!test")
public class PostgresSemanticSearchAdapter implements MeasuredSemanticSearchPort {
    static final int INITIAL_CANDIDATE_MIN = 64;
    static final int MAX_CANDIDATES = 1000;
    static final int MAX_ROUNDS = 4;
    private static final String SEARCH_SQL = """
            WITH ann AS MATERIALIZED (
                SELECT e.retrieval_unit_id,
                       e.dataset_id,
                       e.document_id,
                       e.document_version_id,
                       e.index_build_id,
                       e.embedding <=> CAST(? AS vector) AS distance
                FROM kb_vector_embedding e
                WHERE e.dataset_id=?
                  AND e.embedding_profile=?
                ORDER BY e.embedding <=> CAST(? AS vector)
                LIMIT ?
            )
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
                   1 - ann.distance AS score
            FROM ann
            JOIN kb_retrieval_unit u
              ON u.id=ann.retrieval_unit_id
             AND u.dataset_id=ann.dataset_id
             AND u.document_id=ann.document_id
             AND u.document_version_id=ann.document_version_id
             AND u.index_build_id=ann.index_build_id
            JOIN kb_document d ON d.id=u.document_id
            JOIN kb_dataset ds ON ds.id=u.dataset_id
            JOIN kb_index_build b ON b.id=u.index_build_id
            WHERE d.delete_time IS NULL
              AND ds.delete_time IS NULL
              AND d.active_version_id=u.document_version_id
              AND d.active_index_build_id=u.index_build_id
              AND b.state='ACTIVE'
            ORDER BY ann.distance
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
        return searchMeasured(request).candidates();
    }

    @Override
    public MeasuredResult searchMeasured(SemanticSearchRequest request) {
        String queryVector = vector(request.queryEmbedding());
        MeasuredResult result = readOnlyTransaction.execute(status -> searchInTransaction(request, queryVector));
        return result == null ? new MeasuredResult(List.of(), 0, initialBudget(request.limit()), true) : result;
    }

    private MeasuredResult searchInTransaction(SemanticSearchRequest request, String queryVector) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.queryTimeoutMs());
        jdbc.execute("SET LOCAL hnsw.iterative_scan = 'relaxed_order'");
        int budget = initialBudget(request.limit());
        int rounds = 0;
        while (true) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs < 1) throw new SemanticSearchTimeoutException("semantic database deadline exhausted");
            jdbc.execute("SET LOCAL statement_timeout = '" + remainingMs + "ms'");
            List<RetrievalCandidate> candidates;
            try {
                candidates = jdbc.query(SEARCH_SQL, mapper(), queryVector, request.datasetId(),
                        request.embeddingProfile(), queryVector, budget, request.limit());
            } catch (DataAccessException error) {
                if (isQueryCanceled(error)) {
                    throw new SemanticSearchTimeoutException("PostgreSQL canceled semantic query", error);
                }
                throw error;
            }
            rounds++;
            if (candidates.size() >= request.limit()) {
                return new MeasuredResult(candidates, rounds - 1, budget, false);
            }
            if (budget >= MAX_CANDIDATES || rounds >= MAX_ROUNDS) {
                return new MeasuredResult(candidates, rounds - 1, budget, true);
            }
            budget = Math.min(MAX_CANDIDATES, budget * 2);
        }
    }

    static int initialBudget(int limit) {
        return Math.min(MAX_CANDIDATES, Math.max(INITIAL_CANDIDATE_MIN, limit * 4));
    }

    static boolean isQueryCanceled(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
            cause = cause.getCause();
        }
        return false;
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
