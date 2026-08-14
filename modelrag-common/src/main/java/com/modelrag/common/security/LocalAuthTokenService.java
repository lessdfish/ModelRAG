package com.modelrag.common.security;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LocalAuthTokenService {
    private final String secret;
    private final long ttlSeconds;

    public LocalAuthTokenService(
            @Value("${modelrag.security.token-secret}") String secret,
            @Value("${modelrag.security.token-ttl-seconds:900}") long ttlSeconds) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("modelrag.security.token-secret must be supplied explicitly");
        }
        this.secret = secret;
        this.ttlSeconds = Math.max(60, Math.min(900, ttlSeconds));
    }

    public String issue(String userId, Set<String> roles, Set<Long> datasetIds) {
        if (userId == null || userId.isBlank() || userId.indexOf('|') >= 0 || userId.length() > 128) {
            throw new IllegalArgumentException("令牌用户标识不合法");
        }
        if (roles != null && roles.stream().anyMatch(role -> role == null || role.isBlank()
                || role.indexOf('|') >= 0 || role.indexOf(',') >= 0)) {
            throw new IllegalArgumentException("令牌角色不合法");
        }
        if (datasetIds != null && datasetIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("令牌知识库范围不合法");
        }
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        String payload = userId.trim() + "|" + joinRoles(roles) + "|" + joinIds(datasetIds) + "|" + expiresAt;
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload);
    }

    public RequestUser parse(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]).getBytes(StandardCharsets.US_ASCII),
                parts[1].getBytes(StandardCharsets.US_ASCII))) throw forbidden();
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw forbidden();
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 4) throw forbidden();
        if (fields[0].isBlank() || fields[0].length() > 128) throw forbidden();
        long expiresAt;
        try {
            expiresAt = Long.parseLong(fields[3]);
        } catch (NumberFormatException e) {
            throw forbidden();
        }
        if (Instant.now().getEpochSecond() > expiresAt) throw forbidden();
        try {
            return new RequestUser(fields[0], roles(fields[1]), ids(fields[2]));
        } catch (RuntimeException error) {
            throw forbidden();
        }
    }

    private Set<String> roles(String value) {
        if (value == null || value.isBlank()) return Set.of("USER");
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private Set<Long> ids(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    private String joinRoles(Set<String> roles) {
        return roles == null ? "" : roles.stream()
                .map(role -> role.toUpperCase(Locale.ROOT))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String joinIds(Set<Long> datasetIds) {
        return datasetIds == null ? "" : datasetIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return encode(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成本地登录令牌", e);
        }
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN, "登录令牌无效或已过期");
    }
}
