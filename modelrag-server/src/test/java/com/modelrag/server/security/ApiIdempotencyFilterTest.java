package com.modelrag.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiIdempotencyFilterTest {
    @Test
    void rejectsInvalidAndDuplicateKeysAndCompletesSuccessfulClaim() throws Exception {
        CapturingJdbc jdbc = new CapturingJdbc();
        ApiIdempotencyFilter filter = new ApiIdempotencyFilter(jdbc, new ObjectMapper());

        MockHttpServletResponse invalid = invoke(filter, "short");
        assertEquals(400, invalid.getStatus());
        assertEquals(0, jdbc.claims);

        jdbc.claimed = false;
        MockHttpServletResponse duplicate = invoke(filter, "request-key-0001");
        assertEquals(409, duplicate.getStatus());
        assertEquals(1, jdbc.claims);

        jdbc.claimed = true;
        MockHttpServletResponse success = invoke(filter, "request-key-0002");
        assertEquals(200, success.getStatus());
        assertEquals(2, jdbc.claims);
        assertEquals(1, jdbc.completions);
    }

    private MockHttpServletResponse invoke(ApiIdempotencyFilter filter, String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v2/datasets");
        request.addHeader("Idempotency-Key", key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class CapturingJdbc extends JdbcTemplate {
        private boolean claimed;
        private int claims;
        private int completions;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            claims++;
            @SuppressWarnings("unchecked")
            T value = (T) Integer.valueOf(1);
            return claimed ? List.of(value) : List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("SET status='COMPLETED'")) completions++;
            return 1;
        }
    }
}
