package com.modelrag.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** PostgreSQL-backed duplicate-write guard for public v2 APIs when a caller supplies an idempotency key. */
final class ApiIdempotencyFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(ApiIdempotencyFilter.class);
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,200}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ApiIdempotencyFilter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v2/")
                || !WRITE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))
                || request.getHeader("Idempotency-Key") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("Idempotency-Key").trim();
        if (!SAFE_KEY.matcher(key).matches()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCode.VALIDATION,
                    "Idempotency-Key 必须为 8～200 位字母、数字或 ._:-");
            return;
        }
        String scope = callerScope(request);
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = request.getRequestURI();
        try {
            if (!claim(scope, method, path, key)) {
                writeError(response, HttpServletResponse.SC_CONFLICT, ErrorCode.DUPLICATE_OPERATION,
                        "相同幂等请求已执行或正在执行");
                return;
            }
        } catch (DataAccessException error) {
            LOG.error("Unable to claim API idempotency key: method={}, path={}", method, path, error);
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "数据库暂不可用");
            return;
        }

        try {
            chain.doFilter(request, response);
            if (response.getStatus() < 400) complete(scope, method, path, key);
            else release(scope, method, path, key);
        } catch (IOException | ServletException | RuntimeException error) {
            release(scope, method, path, key);
            throw error;
        }
    }

    private boolean claim(String scope, String method, String path, String key) {
        List<Integer> rows = jdbc.query("""
                INSERT INTO kb_api_idempotency(caller_scope,http_method,request_path,idempotency_key,status,created_at)
                VALUES (?,?,?,?,'PROCESSING',NOW())
                ON CONFLICT(caller_scope,http_method,request_path,idempotency_key) DO UPDATE SET
                    status='PROCESSING',created_at=NOW(),completed_at=NULL
                WHERE kb_api_idempotency.created_at < NOW() - INTERVAL '24 hours'
                RETURNING 1
                """, (rs, row) -> rs.getInt(1), scope, method, path, key);
        return !rows.isEmpty();
    }

    private void complete(String scope, String method, String path, String key) {
        try {
            jdbc.update("""
                    UPDATE kb_api_idempotency SET status='COMPLETED',completed_at=NOW()
                    WHERE caller_scope=? AND http_method=? AND request_path=? AND idempotency_key=?
                    """, scope, method, path, key);
        } catch (DataAccessException error) {
            LOG.error("Unable to complete API idempotency claim: method={}, path={}", method, path, error);
        }
    }

    private void release(String scope, String method, String path, String key) {
        try {
            jdbc.update("""
                    DELETE FROM kb_api_idempotency
                    WHERE caller_scope=? AND http_method=? AND request_path=? AND idempotency_key=? AND status='PROCESSING'
                    """, scope, method, path, key);
        } catch (DataAccessException error) {
            LOG.error("Unable to release failed API idempotency claim: method={}, path={}", method, path, error);
        }
    }

    private String callerScope(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String identity = authentication != null && authentication.isAuthenticated()
                ? authentication.getName() : request.getHeader("Authorization");
        return sha256(identity == null || identity.isBlank() ? "anonymous" : identity);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), ApiResponse.fail(code.code, message));
    }
}
