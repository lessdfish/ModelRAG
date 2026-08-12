package com.modelrag.server.auth;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.LocalSecurityStore;
import com.modelrag.common.security.LocalAuthTokenService;
import com.modelrag.common.security.RequestUser;
import com.modelrag.knowledge.service.KnowledgeStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final LocalAuthTokenService tokens;
    private final AccessControlService access;
    private final String username;
    private final String password;
    private final Set<String> roles;
    private final Set<Long> datasetIds;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<LocalSecurityStore> localSecurity;
    private final KnowledgeStore knowledge;

    @Autowired
    public AuthController(
            LocalAuthTokenService tokens,
            AccessControlService access,
            @Value("${modelrag.security.local-admin-username:admin}") String username,
            @Value("${modelrag.security.local-admin-password:modelrag}") String password,
            @Value("${modelrag.security.local-admin-roles:ADMIN,APPROVER}") String roles,
            @Value("${modelrag.security.local-admin-dataset-ids:}") String datasetIds,
            ObjectProvider<JdbcTemplate> jdbc,
            ObjectProvider<LocalSecurityStore> localSecurity,
            KnowledgeStore knowledge) {
        this.tokens = tokens;
        this.access = access;
        this.username = username;
        this.password = password;
        this.roles = roles(roles);
        this.datasetIds = ids(datasetIds);
        this.jdbc = jdbc;
        this.localSecurity = localSecurity;
        this.knowledge = knowledge;
    }

    public AuthController(LocalAuthTokenService tokens, AccessControlService access, String username, String password,
            String roles, String datasetIds, ObjectProvider<JdbcTemplate> jdbc) {
        this(tokens, access, username, password, roles, datasetIds, jdbc, null, null);
    }

    public AuthController(LocalAuthTokenService tokens, AccessControlService access, String username, String password,
            String roles, String datasetIds, ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<LocalSecurityStore> localSecurity) {
        this(tokens, access, username, password, roles, datasetIds, jdbc, localSecurity, null);
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        RequestUser managedUser = managedLogin(request);
        if (managedUser != null) {
            return ApiResponse.success(new LoginResponse(tokens.issue(managedUser.id(), managedUser.roles(), managedUser.datasetIds()), managedUser));
        }
        if (request == null || !username.equals(request.username()) || !password.equals(request.password())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户名或密码错误");
        }
        RequestUser user = new RequestUser(username, roles, datasetIds);
        return ApiResponse.success(new LoginResponse(tokens.issue(user.id(), user.roles(), user.datasetIds()), user));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@RequestBody RegisterRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (request.password() == null || request.password().length() < 4) {
            throw new IllegalArgumentException("密码至少 4 位");
        }
        String userId = request.username().trim();
        String displayName = request.displayName() == null || request.displayName().isBlank() ? userId : request.displayName().trim();
        Set<Long> grants = currentDatasetIds();
        RequestUser user = registerManagedUser(userId, displayName, request.password(), grants);
        return ApiResponse.success(new LoginResponse(tokens.issue(user.id(), user.roles(), user.datasetIds()), user));
    }

    @GetMapping("/me")
    public ApiResponse<RequestUser> me() {
        return ApiResponse.success(access.currentUser());
    }

    public record LoginRequest(String username, String password) {}
    public record RegisterRequest(String username, String password, String displayName) {}
    public record LoginResponse(String token, RequestUser user) {}

    private Set<String> roles(String value) {
        return Arrays.stream((value == null ? "" : value).split(","))
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

    private RequestUser managedLogin(LoginRequest request) {
        RequestUser databaseUser = databaseLogin(request);
        return databaseUser == null ? localLogin(request) : databaseUser;
    }

    private RequestUser databaseLogin(LoginRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()) return null;
        JdbcTemplate db = jdbc == null ? null : jdbc.getIfAvailable();
        if (db == null) return null;
        try {
            var rows = db.queryForList("SELECT password_hash,enabled FROM kb_user_account WHERE user_id=?", request.username());
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            if (Boolean.FALSE.equals(row.get("enabled"))) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "用户已禁用");
            }
            if (!passwordMatches(String.valueOf(row.get("password_hash")), request.password())) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "用户名或密码错误");
            }
            Set<String> dbRoles = db.queryForList("SELECT role_name FROM kb_user_role WHERE user_id=?", String.class, request.username())
                    .stream()
                    .map(role -> role.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            Set<Long> dbDatasets = db.queryForList(
                            "SELECT dataset_id FROM kb_dataset_acl WHERE user_id=? AND permission IN ('READ','WRITE','ADMIN')",
                            Long.class, request.username())
                    .stream()
                    .collect(Collectors.toSet());
            return new RequestUser(request.username(), dbRoles.isEmpty() ? Set.of("USER") : dbRoles, dbDatasets);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            return null;
        }
    }

    private RequestUser localLogin(LoginRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()) return null;
        LocalSecurityStore store = localSecurity == null ? null : localSecurity.getIfAvailable();
        if (store == null || !store.exists(request.username())) return null;
        if (!store.enabled(request.username())) throw new BusinessException(ErrorCode.FORBIDDEN, "用户已禁用");
        if (!passwordMatches(store.passwordHash(request.username()), request.password())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户名或密码错误");
        }
        return new RequestUser(request.username(), store.roles(request.username()), store.datasetIds(request.username()));
    }

    private RequestUser registerManagedUser(String userId, String displayName, String rawPassword, Set<Long> grants) {
        JdbcTemplate db = jdbc == null ? null : jdbc.getIfAvailable();
        if (db != null) try {
            if (!db.queryForList("SELECT user_id FROM kb_user_account WHERE user_id=?", userId).isEmpty()) {
                throw new IllegalArgumentException("用户已存在: " + userId);
            }
            db.update("INSERT INTO kb_user_account(user_id,display_name,password_hash,enabled) VALUES (?,?,?,TRUE)",
                    userId, displayName, "{sha256}" + sha256(rawPassword));
            db.update("INSERT INTO kb_user_role(user_id,role_name) VALUES (?,'USER') ON CONFLICT(user_id,role_name) DO NOTHING", userId);
            for (Long datasetId : grants) {
                db.update("INSERT INTO kb_dataset_acl(dataset_id,user_id,permission) VALUES (?,?,'READ') ON CONFLICT(dataset_id,user_id) DO UPDATE SET permission='READ'",
                        datasetId, userId);
            }
            return new RequestUser(userId, Set.of("USER"), grants);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {
            // fall back to local store for the default in-memory profile
        }
        LocalSecurityStore store = localSecurity == null ? null : localSecurity.getIfAvailable();
        if (store == null) throw new IllegalStateException("当前环境未启用用户注册存储");
        if (store.exists(userId)) throw new IllegalArgumentException("用户已存在: " + userId);
        store.upsert(userId, displayName, "{sha256}" + sha256(rawPassword), true, Set.of("USER"));
        for (Long datasetId : grants) store.grant(userId, datasetId);
        return new RequestUser(userId, Set.of("USER"), grants);
    }

    private Set<Long> currentDatasetIds() {
        if (knowledge == null) return Set.of();
        return knowledge.datasets().stream().map(dataset -> dataset.id()).collect(Collectors.toSet());
    }

    private boolean passwordMatches(String stored, String raw) {
        if (stored == null || raw == null) return false;
        if (stored.startsWith("{plain}")) return stored.substring(7).equals(raw);
        if (stored.startsWith("{sha256}")) return stored.substring(8).equalsIgnoreCase(sha256(raw));
        return stored.equals(raw);
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法校验密码", e);
        }
    }
}
