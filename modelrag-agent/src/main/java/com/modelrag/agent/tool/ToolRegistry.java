package com.modelrag.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.net.URI;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persistent tool registry. Built-ins are inserted idempotently into PostgreSQL. */
@Service
@Profile("!test")
public class ToolRegistry {
    private final JdbcTemplate jdbc;
    private final ToolSecretCipher secrets;
    private final ObjectMapper json = new ObjectMapper();

    public ToolRegistry(JdbcTemplate jdbc, ToolSecretCipher secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        ensureBuiltin(new ToolDefinition("knowledge_lookup", "检索已授权知识库", "LOW", true,
                "INTERNAL", null, null, null, null, Set.of(), Set.of(), true));
        ensureBuiltin(new ToolDefinition("destructive_operation", "执行删除或变更操作", "HIGH", true));
    }

    public ToolDefinition register(ToolDefinition tool) {
        ToolDefinition normalized = normalize(tool);
        jdbc.update("""
                INSERT INTO kb_tool_definition(name,description,risk_level,enabled,type,endpoint,auth_header_name,
                auth_header_value,json_schema,allowed_roles,allowed_dataset_ids,idempotent,update_time)
                VALUES (?,?,?,?,?,?,?, ?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,NOW())
                ON CONFLICT(name) DO UPDATE SET description=EXCLUDED.description,risk_level=EXCLUDED.risk_level,
                enabled=EXCLUDED.enabled,type=EXCLUDED.type,endpoint=EXCLUDED.endpoint,
                auth_header_name=EXCLUDED.auth_header_name,auth_header_value=EXCLUDED.auth_header_value,
                json_schema=EXCLUDED.json_schema,allowed_roles=EXCLUDED.allowed_roles,
                allowed_dataset_ids=EXCLUDED.allowed_dataset_ids,idempotent=EXCLUDED.idempotent,update_time=NOW()
                """, normalized.name(), normalized.description(), normalized.riskLevel(), normalized.enabled(), normalized.type(),
                normalized.endpoint(), normalized.authHeaderName(), secrets.encrypt(normalized.authHeaderValue()),
                normalized.jsonSchema(), write(normalized.allowedRoles()), write(normalized.allowedDatasetIds()),
                normalized.idempotent());
        return get(normalized.name());
    }

    public ToolDefinition remove(String name) {
        ToolDefinition removed = jdbc.query("""
                SELECT name,description,risk_level,enabled,type,endpoint,auth_header_name,auth_header_value,
                       json_schema::text,allowed_roles::text,allowed_dataset_ids::text,idempotent
                FROM kb_tool_definition WHERE name=?
                """, (rs, n) -> normalize(new ToolDefinition(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getString(7), secrets.decrypt(rs.getString(8)),
                rs.getString(9), roles(rs.getString(10)), datasetIds(rs.getString(11)), rs.getBoolean(12))), name).stream()
                .findFirst().orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
        jdbc.update("DELETE FROM kb_tool_definition WHERE name=?", name);
        return removed;
    }

    public ToolDefinition setEnabled(String name, boolean enabled) {
        ToolDefinition old = getAny(name);
        jdbc.update("UPDATE kb_tool_definition SET enabled=?,update_time=NOW() WHERE name=?", enabled, name);
        return getAny(old.name());
    }

    public ToolDefinition get(String name) {
        ToolDefinition tool = getAny(name);
        if (!tool.enabled()) throw new IllegalArgumentException("工具不可用: " + name);
        return tool;
    }

    public ToolDefinition getAny(String name) {
        return jdbc.query("""
                SELECT name,description,risk_level,enabled,type,endpoint,auth_header_name,auth_header_value,
                       json_schema::text,allowed_roles::text,allowed_dataset_ids::text,idempotent
                FROM kb_tool_definition WHERE name=?
                """, (rs, n) -> normalize(new ToolDefinition(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getString(7), secrets.decrypt(rs.getString(8)),
                rs.getString(9), roles(rs.getString(10)), datasetIds(rs.getString(11)), rs.getBoolean(12))), name).stream()
                .findFirst().orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
    }

    public List<ToolDefinition> list() {
        return jdbc.query("""
                SELECT name,description,risk_level,enabled,type,endpoint,auth_header_name,auth_header_value,
                       json_schema::text,allowed_roles::text,allowed_dataset_ids::text,idempotent
                FROM kb_tool_definition ORDER BY name
                """, (rs, n) -> normalize(new ToolDefinition(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getString(7), secrets.decrypt(rs.getString(8)),
                rs.getString(9), roles(rs.getString(10)), datasetIds(rs.getString(11)), rs.getBoolean(12))));
    }

    public List<ToolDefinition> listEnabled() { return list().stream().filter(ToolDefinition::enabled).toList(); }

    private void ensureBuiltin(ToolDefinition tool) {
        jdbc.update("""
                INSERT INTO kb_tool_definition(name,description,risk_level,enabled,type,json_schema,allowed_roles,allowed_dataset_ids)
                VALUES (?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb)) ON CONFLICT(name) DO NOTHING
                """, tool.name(), tool.description(), tool.riskLevel(), tool.enabled(), tool.type(), "{}", "[]", "[]");
    }

    private ToolDefinition normalize(ToolDefinition tool) {
        validate(tool);
        return new ToolDefinition(tool.name().trim(), tool.description(), risk(tool.riskLevel()), tool.enabled(),
                type(tool.type()), blank(tool.endpoint()), blank(tool.authHeaderName()), blank(tool.authHeaderValue()),
                schema(tool.jsonSchema()), roles(tool.allowedRoles()), datasetIds(tool.allowedDatasetIds()),
                tool.idempotent());
    }

    private void validate(ToolDefinition tool) {
        if (tool == null || tool.name() == null || tool.name().isBlank()) throw new IllegalArgumentException("工具名称不能为空");
        if (!tool.name().trim().matches("[A-Za-z0-9._-]{1,100}")) throw new IllegalArgumentException("工具名称格式不合法");
        if (tool.description() != null && tool.description().length() > 500) throw new IllegalArgumentException("工具描述过长");
        if (!Set.of("LOW", "HIGH", "EXTERNAL_SIDE_EFFECT").contains(risk(tool.riskLevel()))) throw new IllegalArgumentException("工具风险级别不合法");
        if (!Set.of("INTERNAL", "HTTP").contains(type(tool.type()))) throw new IllegalArgumentException("工具类型不合法");
        if ("HTTP".equals(type(tool.type())) && blank(tool.endpoint()) == null) throw new IllegalArgumentException("HTTP 工具必须配置 endpoint");
        if (tool.endpoint() != null) validateEndpoint(tool.endpoint());
        if (tool.authHeaderName() != null && !tool.authHeaderName().isBlank()
                && !tool.authHeaderName().trim().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")) {
            throw new IllegalArgumentException("HTTP 认证 Header 名称不合法");
        }
        if (tool.authHeaderValue() != null && (tool.authHeaderValue().length() > 8192
                || tool.authHeaderValue().contains("\r") || tool.authHeaderValue().contains("\n"))) {
            throw new IllegalArgumentException("HTTP 认证 Header 值不合法");
        }
        if (tool.jsonSchema() != null && tool.jsonSchema().length() > 65_536) throw new IllegalArgumentException("工具 JSON Schema 过长");
        if (tool.allowedDatasetIds() != null && tool.allowedDatasetIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("工具知识库权限不合法");
        }
        if (tool.allowedRoles() != null && tool.allowedRoles().stream().anyMatch(role -> role == null
                || !role.trim().matches("[A-Za-z0-9_-]{1,80}"))) {
            throw new IllegalArgumentException("工具角色权限不合法");
        }
    }

    private void validateEndpoint(String value) {
        if (value.length() > 2048) throw new IllegalArgumentException("HTTP 工具 endpoint 过长");
        try {
            URI uri = URI.create(value.trim());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("HTTP 工具 endpoint 不合法");
            }
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException) throw (IllegalArgumentException) error;
            throw new IllegalArgumentException("HTTP 工具 endpoint 不合法", error);
        }
    }

    private String risk(String value) { return value == null ? "LOW" : value.trim().toUpperCase(Locale.ROOT); }
    private String type(String value) { return value == null || value.isBlank() ? "INTERNAL" : value.trim().toUpperCase(Locale.ROOT); }
    private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Set<String> roles(Set<String> values) { return values == null ? Set.of() : values.stream().filter(value -> value != null && !value.isBlank()).map(value -> value.trim().toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private Set<Long> datasetIds(Set<Long> values) { return values == null ? Set.of() : Set.copyOf(values); }
    private Set<String> roles(String value) {
        try {
            return roles(Set.copyOf(json.readValue(value == null ? "[]" : value,
                    new TypeReference<List<String>>() {})));
        } catch (Exception error) {
            throw new IllegalStateException("工具角色权限数据无效，已拒绝加载", error);
        }
    }

    private Set<Long> datasetIds(String value) {
        try {
            return Set.copyOf(json.readValue(value == null ? "[]" : value,
                    new TypeReference<List<Long>>() {}));
        } catch (Exception error) {
            throw new IllegalStateException("工具知识库权限数据无效，已拒绝加载", error);
        }
    }
    private String schema(String value) { String text = value == null || value.isBlank() ? "{}" : value.trim(); try { JsonNode node = json.readTree(text); if (!node.isObject()) throw new IllegalArgumentException("工具 JSON Schema 必须是对象"); return text; } catch (IllegalArgumentException e) { throw e; } catch (Exception e) { throw new IllegalArgumentException("工具 JSON Schema 无法解析"); } }
    private String write(Object value) { try { return json.writeValueAsString(value == null ? List.of() : value); } catch (Exception e) { throw new IllegalStateException("工具权限序列化失败", e); } }
}
