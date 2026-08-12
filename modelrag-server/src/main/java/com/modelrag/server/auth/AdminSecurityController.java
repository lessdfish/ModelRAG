package com.modelrag.server.auth;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.LocalSecurityStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/security")
public class AdminSecurityController {
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final AccessControlService access;
    private final ObjectProvider<LocalSecurityStore> localSecurity;

    @Autowired
    public AdminSecurityController(ObjectProvider<JdbcTemplate> jdbc, AccessControlService access,
            ObjectProvider<LocalSecurityStore> localSecurity) {
        this.jdbc = jdbc;
        this.access = access;
        this.localSecurity = localSecurity;
    }

    public AdminSecurityController(ObjectProvider<JdbcTemplate> jdbc, AccessControlService access) {
        this(jdbc, access, null);
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> users() {
        access.requireRole("ADMIN");
        JdbcTemplate db = database();
        if (db == null) return ApiResponse.success(local().users());
        List<Map<String, Object>> users = db.queryForList("""
                SELECT user_id,display_name,enabled,create_time,update_time
                FROM kb_user_account
                ORDER BY user_id
                """);
        return ApiResponse.success(users.stream().map(row -> enrich(db, row)).toList());
    }

    @PostMapping("/users")
    public ApiResponse<Map<String, Object>> upsertUser(@RequestBody Map<String, Object> body) {
        access.requireRole("ADMIN");
        JdbcTemplate db = database();
        String userId = required(body, "userId");
        String displayName = Objects.toString(body.getOrDefault("displayName", userId), userId);
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        String password = Objects.toString(body.getOrDefault("password", ""), "");
        Set<String> roles = roles(body.get("roles"));
        if (db == null) {
            return ApiResponse.success(local().upsert(userId, displayName,
                    password.isBlank() ? null : "{sha256}" + sha256(password), enabled, roles));
        }
        if (password.isBlank()) {
            Integer existing = db.queryForObject("SELECT COUNT(*) FROM kb_user_account WHERE user_id=?", Integer.class, userId);
            if (existing == null || existing == 0) throw new IllegalArgumentException("新用户密码不能为空");
            db.update("""
                    UPDATE kb_user_account
                    SET display_name=?,enabled=?,update_time=NOW()
                    WHERE user_id=?
                    """, displayName, enabled, userId);
        } else {
            db.update("""
                    INSERT INTO kb_user_account(user_id,display_name,password_hash,enabled,update_time)
                    VALUES (?,?,?,?,NOW())
                    ON CONFLICT(user_id) DO UPDATE SET display_name=EXCLUDED.display_name,password_hash=EXCLUDED.password_hash,enabled=EXCLUDED.enabled,update_time=NOW()
                    """, userId, displayName, "{sha256}" + sha256(password), enabled);
        }
        replaceRoles(db, userId, roles);
        return ApiResponse.success(enrich(db, Map.of("user_id", userId, "display_name", displayName, "enabled", enabled)));
    }

    @PostMapping("/users/{userId}/datasets/{datasetId}")
    public ApiResponse<Map<String, Object>> grantDataset(@PathVariable String userId, @PathVariable long datasetId,
            @RequestBody(required = false) Map<String, Object> body) {
        access.requireRole("ADMIN");
        JdbcTemplate db = database();
        if (db == null) return ApiResponse.success(local().grant(userId, datasetId));
        String permission = permission(body == null ? null : body.get("permission"));
        db.update("""
                INSERT INTO kb_dataset_acl(dataset_id,user_id,permission)
                VALUES (?,?,?)
                ON CONFLICT(dataset_id,user_id) DO UPDATE SET permission=EXCLUDED.permission
                """, datasetId, userId, permission);
        return ApiResponse.success(Map.of("userId", userId, "datasetId", datasetId, "permission", permission));
    }

    @DeleteMapping("/users/{userId}/datasets/{datasetId}")
    public ApiResponse<Void> revokeDataset(@PathVariable String userId, @PathVariable long datasetId) {
        access.requireRole("ADMIN");
        JdbcTemplate db = database();
        if (db == null) local().revoke(userId, datasetId);
        else db.update("DELETE FROM kb_dataset_acl WHERE user_id=? AND dataset_id=?", userId, datasetId);
        return ApiResponse.success(null);
    }

    private Map<String, Object> enrich(JdbcTemplate db, Map<String, Object> row) {
        String userId = Objects.toString(row.get("user_id"), Objects.toString(row.get("userId"), ""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("displayName", row.getOrDefault("display_name", row.get("displayName")));
        result.put("enabled", row.get("enabled"));
        result.put("roles", db.queryForList("SELECT role_name FROM kb_user_role WHERE user_id=? ORDER BY role_name", String.class, userId));
        result.put("datasetIds", db.queryForList("SELECT dataset_id FROM kb_dataset_acl WHERE user_id=? ORDER BY dataset_id", Long.class, userId));
        result.put("createdAt", row.get("create_time"));
        result.put("updatedAt", row.get("update_time"));
        return result;
    }

    private void replaceRoles(JdbcTemplate db, String userId, Set<String> roles) {
        db.update("DELETE FROM kb_user_role WHERE user_id=?", userId);
        for (String role : roles.isEmpty() ? Set.of("USER") : roles) {
            db.update("INSERT INTO kb_user_role(user_id,role_name) VALUES (?,?) ON CONFLICT(user_id,role_name) DO NOTHING", userId, role);
        }
    }

    private Set<String> roles(Object value) {
        if (value instanceof Iterable<?> items) {
            return toRoles(items);
        }
        return toRoles(Arrays.asList(Objects.toString(value == null ? "USER" : value, "USER").split(",")));
    }

    private Set<String> toRoles(Iterable<?> items) {
        return java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(item -> Objects.toString(item, "").trim().toUpperCase(Locale.ROOT))
                .filter(role -> !role.isBlank())
                .collect(Collectors.toSet());
    }

    private String permission(Object value) {
        String permission = Objects.toString(value == null ? "READ" : value, "READ").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("READ", "WRITE", "ADMIN").contains(permission)) throw new IllegalArgumentException("知识库权限只能是 READ、WRITE 或 ADMIN");
        return permission;
    }

    private String required(Map<String, Object> body, String key) {
        String value = Objects.toString(body == null ? null : body.get(key), "").trim();
        if (value.isBlank()) throw new IllegalArgumentException(key + " 不能为空");
        return value;
    }

    private JdbcTemplate database() {
        return jdbc == null ? null : jdbc.getIfAvailable();
    }

    private LocalSecurityStore local() {
        LocalSecurityStore store = localSecurity == null ? null : localSecurity.getIfAvailable();
        if (store == null) throw new IllegalStateException("本地用户权限存储不可用");
        return store;
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成密码哈希", e);
        }
    }
}
