package com.modelrag.server;

import com.modelrag.common.vector.SearchRequest;
import com.modelrag.indexing.store.PostgresVectorStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresVectorStoreTest {
    @Test
    void vectorSearchOnlyUsesReadyActiveDocuments() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        new PostgresVectorStore(jdbc, transactions).search(new SearchRequest(7, new float[1024], 5));
        assertTrue(jdbc.sql.contains("d.active_index_version > 0"));
        assertTrue(jdbc.sql.contains("c.version=d.active_index_version"));
        assertTrue(jdbc.sql.contains("c.delete_time IS NULL"));
        assertTrue(jdbc.sql.contains("d.delete_time IS NULL"));
    }

    static class CapturingJdbcTemplate extends JdbcTemplate {
        String sql = "";
        @Override public void execute(String sql) { }
        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            return List.of();
        }
    }
}
