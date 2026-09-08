package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.search.channel.v2.PostgresSemanticSearchAdapter;
import com.modelrag.search.channel.v2.SemanticSearchRequest;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class G5SemanticRetrievalTest {
    @Test
    void candidateIdentityIsRetrievalUnitAndNotLegacyChunk() {
        RetrievalCandidate candidate = new RetrievalCandidate(7, 17, 19, 23, 29, 31,
                RetrievalUnitType.SECTION, "Root / Section", "content", .8,
                RetrievalChannel.SEMANTIC, 1, Map.of("language", "zh"));

        assertEquals(17, candidate.retrievalUnitId());
        assertEquals(RetrievalChannel.SEMANTIC, candidate.channel());
        assertEquals("zh", candidate.metadata().get("language"));
    }

    @Test
    void semanticRequestRejectsUnboundedOrInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new SemanticSearchRequest(1, new float[] {1}, "qwen3-v1", 0));
        assertThrows(IllegalArgumentException.class, () -> new SemanticSearchRequest(1, new float[] {1}, "qwen3-v1", 501));
    }

    @Test
    void semanticSqlContainsPostgresActiveBuildAndVersionGuards() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = immediateTransactions();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        PostgresSemanticSearchAdapter adapter = new PostgresSemanticSearchAdapter(
                jdbc, transactions, new ObjectMapper());
        adapter.search(new SemanticSearchRequest(7, new float[] {1, 2}, "qwen3-v1", 12));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("[1.0,2.0]"), eq(7L),
                eq("qwen3-v1"), eq("[1.0,2.0]"), eq(12));
        String query = sql.getValue();
        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("d.active_version_id=u.document_version_id")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("d.active_index_build_id=u.index_build_id")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("b.state='ACTIVE'")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("e.embedding_profile=?")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("LIMIT ?")));
    }

    private PlatformTransactionManager immediateTransactions() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return mock(TransactionStatus.class);
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }
}
