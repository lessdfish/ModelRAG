package com.modelrag.server.auth;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.RequestUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RefreshTokenService {
    private static final long TTL_SECONDS = 30L * 24 * 60 * 60;
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public String issue(String userId) {
        String raw = randomToken();
        jdbc.update("INSERT INTO kb_refresh_token(token_id,user_id,token_hash,expires_at) VALUES (?,?,?,?)",
                UUID.randomUUID(), userId, hash(raw), java.sql.Timestamp.from(Instant.now().plusSeconds(TTL_SECONDS)));
        return raw;
    }

    @Transactional
    public RequestUser rotate(String raw) {
        if (raw == null || raw.isBlank()) throw invalid();
        String hash = hash(raw);
        var rows = jdbc.query("""
                SELECT t.user_id,t.expires_at,u.enabled
                FROM kb_refresh_token t JOIN kb_user_account u ON u.user_id=t.user_id
                WHERE t.token_hash=? AND t.revoked_at IS NULL
                """, (rs, n) -> new TokenRow(rs.getString("user_id"), rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("enabled")), hash);
        if (rows.isEmpty() || !rows.get(0).enabled() || Instant.now().isAfter(rows.get(0).expiresAt())) throw invalid();
        jdbc.update("UPDATE kb_refresh_token SET revoked_at=NOW() WHERE token_hash=? AND revoked_at IS NULL", hash);
        return loadUser(rows.get(0).userId());
    }

    public void revoke(String raw) {
        if (raw != null && !raw.isBlank()) jdbc.update("UPDATE kb_refresh_token SET revoked_at=NOW() WHERE token_hash=?", hash(raw));
    }

    private RequestUser loadUser(String userId) {
        Set<String> roles = jdbc.queryForList("SELECT role_name FROM kb_user_role WHERE user_id=?", String.class, userId)
                .stream().map(String::toUpperCase).collect(Collectors.toSet());
        java.util.Map<Long, String> permissions = jdbc.query(
                "SELECT dataset_id,permission FROM kb_dataset_acl WHERE user_id=? AND permission IN ('READ','WRITE','ADMIN')",
                rs -> {
                    java.util.Map<Long, String> result = new java.util.LinkedHashMap<>();
                    while (rs.next()) result.put(rs.getLong(1), rs.getString(2));
                    return result;
                }, userId);
        return new RequestUser(userId, roles.isEmpty() ? Set.of("USER") : roles,
                permissions.keySet(), permissions);
    }

    private String randomToken() {
        byte[] value = new byte[48];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("无法生成 refresh token 摘要", error); }
    }

    private BusinessException invalid() { return new BusinessException(ErrorCode.FORBIDDEN, "Refresh Token 无效或已过期"); }
    private record TokenRow(String userId, Instant expiresAt, boolean enabled) {}
}
