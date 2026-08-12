package com.modelrag.server;

import com.modelrag.common.vector.SearchRequest;
import com.modelrag.indexing.store.PostgresVectorStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresVectorStoreTest {
    @Test
    void vectorSearchOnlyUsesReadyActiveDocuments() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        new PostgresVectorStore(jdbc).search(new SearchRequest(7, new float[1024], 5));
        assertTrue(jdbc.sql.contains("d.index_status='READY'"));
        assertTrue(jdbc.sql.contains("c.delete_time IS NULL"));
        assertTrue(jdbc.sql.contains("d.delete_time IS NULL"));
    }

    static class CapturingJdbcTemplate extends JdbcTemplate {
        String sql = "";
        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            return List.of();
        }
    }
}
