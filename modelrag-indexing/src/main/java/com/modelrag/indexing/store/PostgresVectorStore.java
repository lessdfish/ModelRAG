package com.modelrag.indexing.store;

import com.modelrag.common.vector.SearchRequest;
import com.modelrag.common.vector.SearchResult;
import com.modelrag.common.vector.VectorDocument;
import com.modelrag.common.vector.VectorStore;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Uses pgvector cosine distance with an HNSW index created by Flyway. */
@Service
@Profile("postgres")
public class PostgresVectorStore implements VectorStore {
    private final JdbcTemplate jdbc;
    public PostgresVectorStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void upsert(List<VectorDocument> documents) { for (VectorDocument d : documents) jdbc.update("UPDATE kb_chunk SET embedding=CAST(? AS vector) WHERE id=?",vector(d.embedding()),d.id()); }
    @Override public List<SearchResult> search(SearchRequest request) { return jdbc.query("""
            SELECT c.id,c.content,1-(c.embedding <=> CAST(? AS vector)) AS score
            FROM kb_chunk c
            JOIN kb_document d ON d.id=c.document_id
            WHERE c.dataset_id=?
              AND c.delete_time IS NULL
              AND d.delete_time IS NULL
              AND d.index_status='READY'
              AND c.embedding IS NOT NULL
            ORDER BY c.embedding <=> CAST(? AS vector)
            LIMIT ?
            """,(rs,n)->new SearchResult(rs.getLong("id"),rs.getString("content"),rs.getDouble("score"),"vector"),vector(request.embedding()),request.datasetId(),vector(request.embedding()),request.topK()); }
    @Override public void deleteDocument(long documentId) { jdbc.update("UPDATE kb_chunk SET delete_time=NOW() WHERE document_id=?",documentId); }
    private String vector(float[] values) { StringBuilder result=new StringBuilder("[");for(int i=0;i<values.length;i++){if(i>0)result.append(',');result.append(values[i]);}return result.append(']').toString(); }
}
