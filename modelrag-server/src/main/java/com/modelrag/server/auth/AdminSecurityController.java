package com.modelrag.server.auth;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping({"/api/v1/admin/security", "/api/v2/admin/security"})
public class AdminSecurityController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AccessControlService access;

    public AdminSecurityController(JdbcTemplate jdbc, PasswordEncoder passwords, AccessControlService access) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.access = access;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserView>> users() {
        access.requireRole("ADMIN");
        return ApiResponse.success(jdbc.query("""
                SELECT user_id,display_name,enabled,create_time,update_time
                FROM kb_user_account ORDER BY user_id
                """, (rs, n) -> user(rs.getString("user_id"), rs.getString("display_name"),
                rs.getBoolean("enabled"), rs.getTimestamp("create_time").toInstant().toString(),
                rs.getTimestamp("update_time").toInstant().toString())));
    }

    @PostMapping("/users")
    public ApiResponse<UserView> upsertUser(@RequestBody UserRequest request) {
        access.requireRole("ADMIN");
        if (request == null || blank(request.userId())) throw new IllegalArgumentException("userId 不能为空");
        Set<String> roles = normalizeRoles(request.roles());
        String userId = request.userId().trim();
        String displayName = blank(request.displayName()) ? userId : request.displayName().trim();
        if (blank(request.password())) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_user_account WHERE user_id=?", Integer.class, userId);
            if (count == null || count == 0) throw new IllegalArgumentException("新用户密码不能为空");
            jdbc.update("UPDATE kb_user_account SET display_name=?,enabled=?,update_time=NOW() WHERE user_id=?",
                    displayName, request.enabled(), userId);
        } else {
            if (request.password().length() < 12) throw new IllegalArgumentException("密码至少 12 位");
            jdbc.update("""
                    INSERT INTO kb_user_account(user_id,display_name,password_hash,enabled,update_time)
                    VALUES (?,?,?,?,NOW())
                    ON CONFLICT(user_id) DO UPDATE SET display_name=EXCLUDED.display_name,
                    password_hash=EXCLUDED.password_hash,enabled=EXCLUDED.enabled,update_time=NOW()
                    """, userId, displayName, passwords.encode(request.password()), request.enabled());
        }
        jdbc.update("DELETE FROM kb_user_role WHERE user_id=?", userId);
        for (String role : roles) jdbc.update("INSERT INTO kb_user_role(user_id,role_name) VALUES (?,?)", userId, role);
        return ApiResponse.success(user(userId, displayName, request.enabled(), null, null));
    }

    @PostMapping("/users/{userId}/datasets/{datasetId}")
    public ApiResponse<DatasetGrant> grantDataset(@PathVariable String userId, @PathVariable long datasetId,
            @RequestBody(required = false) DatasetGrantRequest request) {
        access.requireRole("ADMIN");
        String permission = normalizePermission(request == null ? null : request.permission());
        jdbc.update("""
                INSERT INTO kb_dataset_acl(dataset_id,user_id,permission) VALUES (?,?,?)
                ON CONFLICT(dataset_id,user_id) DO UPDATE SET permission=EXCLUDED.permission
                """, datasetId, userId, permission);
        return ApiResponse.success(new DatasetGrant(userId, datasetId, permission));
    }

    @DeleteMapping("/users/{userId}/datasets/{datasetId}")
    public ApiResponse<Void> revokeDataset(@PathVariable String userId, @PathVariable long datasetId) {
        access.requireRole("ADMIN");
        jdbc.update("DELETE FROM kb_dataset_acl WHERE user_id=? AND dataset_id=?", userId, datasetId);
        return ApiResponse.success(null);
    }

    public record UserRequest(String userId, String displayName, String password, boolean enabled, Set<String> roles) {}
    public record DatasetGrantRequest(String permission) {}
    public record DatasetGrant(String userId, long datasetId, String permission) {}
    public record UserView(String userId, String displayName, boolean enabled, Set<String> roles,
            Set<Long> datasetIds, String createdAt, String updatedAt) {}

    private UserView user(String userId, String displayName, boolean enabled, String createdAt, String updatedAt) {
        Set<String> roles = jdbc.queryForList("SELECT role_name FROM kb_user_role WHERE user_id=? ORDER BY role_name", String.class, userId)
                .stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        Set<Long> datasets = jdbc.queryForList("SELECT dataset_id FROM kb_dataset_acl WHERE user_id=? ORDER BY dataset_id", Long.class, userId)
                .stream().collect(Collectors.toSet());
        return new UserView(userId, displayName, enabled, roles, datasets, createdAt, updatedAt);
    }

    private Set<String> normalizeRoles(Set<String> values) {
        Set<String> result = values == null ? Set.of("USER") : values.stream().map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).collect(Collectors.toSet());
        return result.isEmpty() ? Set.of("USER") : result;
    }

    private String normalizePermission(String value) {
        String normalized = value == null ? "READ" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("READ", "WRITE", "ADMIN").contains(normalized)) throw new IllegalArgumentException("权限只能是 READ、WRITE 或 ADMIN");
        return normalized;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
