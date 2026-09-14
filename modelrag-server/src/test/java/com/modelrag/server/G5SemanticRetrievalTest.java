package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.search.channel.v2.PostgresSemanticSearchAdapter;
import com.modelrag.search.channel.v2.MeasuredSemanticSearchPort;
import com.modelrag.search.channel.v2.SemanticSearchRequest;
import com.modelrag.search.channel.v2.SemanticSearchTimeoutException;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import java.util.List;
import java.util.Map;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
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
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(candidates(12));

        PostgresSemanticSearchAdapter adapter = new PostgresSemanticSearchAdapter(
                jdbc, transactions, new ObjectMapper());
        adapter.search(new SemanticSearchRequest(7, new float[] {1, 2}, "qwen3-v1", 12));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("[1.0,2.0]"), eq(7L),
                eq("qwen3-v1"), eq("[1.0,2.0]"), eq(64), eq(12));
        String query = sql.getValue();
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertTrue(query.contains("WITH ann AS MATERIALIZED")),
                () -> assertTrue(query.indexOf("LIMIT ?") < query.indexOf("FROM ann")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("d.active_version_id=u.document_version_id")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("d.active_index_build_id=u.index_build_id")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("b.state='ACTIVE'")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("e.embedding_profile=?")),
                () -> org.junit.jupiter.api.Assertions.assertTrue(query.contains("LIMIT ?")));
    }

    @Test
    void semanticAnnRefillIsBoundedAndReportsExhaustion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(candidates(2), candidates(3));
        PostgresSemanticSearchAdapter adapter = new PostgresSemanticSearchAdapter(
                jdbc, immediateTransactions(), new ObjectMapper());

        MeasuredSemanticSearchPort.MeasuredResult refilled = adapter.searchMeasured(
                new SemanticSearchRequest(7, new float[] {1, 2}, "qwen3-v1", 3, 500));
        assertEquals(3, refilled.candidates().size());
        assertEquals(1, refilled.refillRounds());
        assertEquals(128, refilled.candidateBudget());
        assertFalse(refilled.refillExhausted());

        JdbcTemplate exhaustedJdbc = mock(JdbcTemplate.class);
        when(exhaustedJdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(candidates(1));
        PostgresSemanticSearchAdapter exhaustedAdapter = new PostgresSemanticSearchAdapter(
                exhaustedJdbc, immediateTransactions(), new ObjectMapper());
        MeasuredSemanticSearchPort.MeasuredResult exhausted = exhaustedAdapter.searchMeasured(
                new SemanticSearchRequest(7, new float[] {1, 2}, "qwen3-v1", 3, 500));
        assertEquals(1, exhausted.candidates().size());
        assertEquals(3, exhausted.refillRounds());
        assertEquals(512, exhausted.candidateBudget());
        assertTrue(exhausted.refillExhausted());
        verify(exhaustedJdbc, times(4)).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void postgresQueryCanceledMapsToSemanticTimeout() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SQLException canceled = new SQLException("canceling statement due to statement timeout", "57014");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("query canceled", canceled));
        PostgresSemanticSearchAdapter adapter = new PostgresSemanticSearchAdapter(
                jdbc, immediateTransactions(), new ObjectMapper());

        assertThrows(SemanticSearchTimeoutException.class, () -> adapter.search(
                new SemanticSearchRequest(7, new float[] {1, 2}, "qwen3-v1", 3, 500)));
    }

    private List<RetrievalCandidate> candidates(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new RetrievalCandidate(7, index + 1, index + 100,
                        index + 200, index + 300, index + 400, RetrievalUnitType.PARAGRAPH,
                        "title", "content", 1 - index / 100d, RetrievalChannel.SEMANTIC,
                        index + 1, Map.of()))
                .toList();
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
