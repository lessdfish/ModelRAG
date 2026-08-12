package com.modelrag.common.security;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessControlService {
    private final HttpServletRequest request;
    private final boolean defaultAdminEnabled;
    private final boolean headerAuthEnabled;
    private final LocalAuthTokenService tokens;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<LocalSecurityStore> localSecurity;

    @Autowired
    public AccessControlService(HttpServletRequest request,
            @Value("${modelrag.security.default-admin-enabled:false}") boolean defaultAdminEnabled,
            @Value("${modelrag.security.header-auth-enabled:true}") boolean headerAuthEnabled,
            LocalAuthTokenService tokens,
            ObjectProvider<JdbcTemplate> jdbc,
            ObjectProvider<LocalSecurityStore> localSecurity) {
        this.request = request;
        this.defaultAdminEnabled = defaultAdminEnabled;
        this.headerAuthEnabled = headerAuthEnabled;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.localSecurity = localSecurity;
    }

    public AccessControlService(HttpServletRequest request, boolean defaultAdminEnabled) {
        this(request, defaultAdminEnabled, new LocalAuthTokenService("modelrag-test-secret", 3600));
    }

    public AccessControlService(HttpServletRequest request, boolean defaultAdminEnabled, LocalAuthTokenService tokens) {
        this.request = request;
        this.defaultAdminEnabled = defaultAdminEnabled;
        this.headerAuthEnabled = true;
        this.tokens = tokens;
        this.jdbc = null;
        this.localSecurity = null;
    }

    public AccessControlService(HttpServletRequest request, boolean defaultAdminEnabled, LocalAuthTokenService tokens,
            ObjectProvider<JdbcTemplate> jdbc) {
        this.request = request;
        this.defaultAdminEnabled = defaultAdminEnabled;
        this.headerAuthEnabled = true;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.localSecurity = null;
    }

    public AccessControlService(HttpServletRequest request, boolean defaultAdminEnabled, LocalAuthTokenService tokens,
            ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<LocalSecurityStore> localSecurity) {
        this.request = request;
        this.defaultAdminEnabled = defaultAdminEnabled;
        this.headerAuthEnabled = true;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.localSecurity = localSecurity;
    }

    public AccessControlService(HttpServletRequest request, boolean defaultAdminEnabled, boolean headerAuthEnabled,
            LocalAuthTokenService tokens, ObjectProvider<JdbcTemplate> jdbc) {
        this.request = request;
        this.defaultAdminEnabled = defaultAdminEnabled;
        this.headerAuthEnabled = headerAuthEnabled;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.localSecurity = null;
    }

    public RequestUser currentUser() {
        String token = bearer();
        if (token != null && !token.isBlank()) return withDatabasePermissions(tokens.parse(token));
        String userId = header("X-User-Id");
        if ((userId == null || userId.isBlank()) && defaultAdminEnabled) {
            return new RequestUser("dev-admin", Set.of("ADMIN", "APPROVER"), Set.of());
        }
        if (!headerAuthEnabled) throw forbidden("缺少用户身份，请使用 Authorization Bearer 登录令牌");
        if (userId == null || userId.isBlank()) throw forbidden("缺少用户身份，请提供 X-User-Id");
        RequestUser headerUser = new RequestUser(userId.trim(), roles(), datasetIds());
        return withDatabasePermissions(headerUser);
    }

    public RequestUser requireRole(String role) {
        RequestUser user = currentUser();
        if (!user.hasRole(role.toUpperCase(Locale.ROOT))) throw forbidden("用户缺少角色: " + role);
        return user;
    }

    public void requireDatasetAccess(long datasetId) {
        RequestUser user = currentUser();
        if (!user.canAccess(datasetId)) throw forbidden("用户无权访问知识库: " + datasetId);
    }

    public void requireAnyDatasetAccess(Set<Long> datasetIds) {
        RequestUser user = currentUser();
        if (user.roles().contains("ADMIN")) return;
        if (datasetIds.stream().noneMatch(user.datasetIds()::contains)) throw forbidden("用户没有可用知识库权限");
    }

    private Set<String> roles() {
        String value = header("X-User-Roles");
        if (value == null || value.isBlank()) return Set.of("USER");
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private Set<Long> datasetIds() {
        String value = header("X-Dataset-Ids");
        if (value == null || value.isBlank()) return Set.of();
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isBlank())
                    .map(Long::parseLong)
                    .collect(Collectors.toSet());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "X-Dataset-Ids 必须是逗号分隔的数字");
        }
    }

    private RequestUser withDatabasePermissions(RequestUser fallback) {
        return databaseUser(fallback.id()).or(() -> localUser(fallback.id())).orElse(fallback);
    }

    private Optional<RequestUser> databaseUser(String userId) {
        JdbcTemplate db = jdbc == null ? null : jdbc.getIfAvailable();
        if (db == null || userId == null || userId.isBlank()) return Optional.empty();
        try {
            Boolean enabled = db.queryForObject("SELECT enabled FROM kb_user_account WHERE user_id=?", Boolean.class, userId);
            if (Boolean.FALSE.equals(enabled)) throw forbidden("用户已禁用: " + userId);
            Set<String> dbRoles = db.queryForList("SELECT role_name FROM kb_user_role WHERE user_id=?", String.class, userId)
                    .stream()
                    .map(role -> role.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            Set<Long> dbDatasets = db.queryForList(
                            "SELECT dataset_id FROM kb_dataset_acl WHERE user_id=? AND permission IN ('READ','WRITE','ADMIN')",
                            Long.class, userId)
                    .stream()
                    .collect(Collectors.toSet());
            return Optional.of(new RequestUser(userId, dbRoles.isEmpty() ? Set.of("USER") : dbRoles, dbDatasets));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<RequestUser> localUser(String userId) {
        LocalSecurityStore store = localSecurity == null ? null : localSecurity.getIfAvailable();
        if (store == null || userId == null || userId.isBlank() || !store.exists(userId)) return Optional.empty();
        if (!store.enabled(userId)) throw forbidden("用户已禁用: " + userId);
        return Optional.of(new RequestUser(userId, store.roles(userId), store.datasetIds(userId)));
    }

    private String header(String name) {
        return request.getHeader(name);
    }

    private String bearer() {
        String value = header("Authorization");
        if (value == null || value.isBlank()) return request.getParameter("access_token");
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : null;
    }

    private BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message);
    }
}
