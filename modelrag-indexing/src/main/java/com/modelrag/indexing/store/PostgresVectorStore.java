package com.modelrag.indexing.store;

import com.modelrag.common.vector.SearchRequest;
import com.modelrag.common.vector.SearchResult;
import com.modelrag.common.vector.VectorDocument;
import com.modelrag.common.vector.VectorStore;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/** Uses pgvector cosine distance with an HNSW index created by Flyway. */
@Service
@Profile("!test")
public class PostgresVectorStore implements VectorStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnlyTransaction;

    public PostgresVectorStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Override public void upsert(List<VectorDocument> documents) {
        for (VectorDocument d : documents) jdbc.update("UPDATE kb_chunk SET embedding=CAST(? AS vector) WHERE id=?", vector(d.embedding()), d.id());
    }

    @Override public List<SearchResult> search(SearchRequest request) {
        List<SearchResult> result = readOnlyTransaction.execute(status -> {
            // pgvector 0.8+ iterative scan keeps filtered ANN searches from returning too few rows.
            jdbc.execute("SET LOCAL hnsw.iterative_scan = 'relaxed_order'");
            return query(request);
        });
        return result == null ? List.of() : result;
    }

    /** Exact sequential-scan path used by offline ANN recall calibration. */
    public List<SearchResult> exactSearch(SearchRequest request) {
        List<SearchResult> result = readOnlyTransaction.execute(status -> {
            jdbc.execute("SET LOCAL enable_indexscan = off");
            jdbc.execute("SET LOCAL enable_bitmapscan = off");
            return query(request);
        });
        return result == null ? List.of() : result;
    }

    private List<SearchResult> query(SearchRequest request) { return jdbc.query("""
            SELECT c.id,c.content,1-(c.embedding <=> CAST(? AS vector)) AS score
            FROM kb_chunk c
            JOIN kb_document d ON d.id=c.document_id
            WHERE c.dataset_id=?
              AND c.delete_time IS NULL
              AND d.delete_time IS NULL
              AND d.active_index_version > 0
              AND c.version=d.active_index_version
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(? AS vector)
            LIMIT ?
            """, (rs, n) -> new SearchResult(rs.getLong("id"), rs.getString("content"), rs.getDouble("score"), "vector"),
            vector(request.embedding()), request.datasetId(), vector(request.embedding()), request.topK()); }

    @Override public void deleteDocument(long documentId) { jdbc.update("UPDATE kb_chunk SET delete_time=NOW() WHERE document_id=?", documentId); }

    private String vector(float[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) { if (i > 0) result.append(','); result.append(values[i]); }
        return result.append(']').toString();
    }
}
