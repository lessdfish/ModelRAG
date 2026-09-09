package com.modelrag.toolgateway.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.toolgateway.security.ToolSecretCipher;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed safe tool catalog and narrow execution-spec resolver. */
@Service
@Profile("!test")
public class JdbcToolCatalog implements ToolCatalog, ToolExecutionResolver {
    private static final String SELECT = """
            SELECT name,description,risk_level,enabled,type,endpoint,auth_header_name,auth_header_value,
                   json_schema::text,allowed_roles::text,allowed_dataset_ids::text,idempotent
            FROM kb_tool_definition
            """;

    private final JdbcTemplate jdbc;
    private final ToolSecretCipher secrets;
    private final ObjectMapper json;

    public JdbcToolCatalog(JdbcTemplate jdbc, ToolSecretCipher secrets, ObjectMapper json) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.json = json;
        ensureBuiltin(new ToolRegistrationCommand("knowledge_lookup", "检索已授权知识库", "LOW", true,
                "INTERNAL", null, null, null, "{}", Set.of(), Set.of(), true));
        ensureBuiltin(new ToolRegistrationCommand("destructive_operation", "执行删除或变更操作", "HIGH", true,
                "INTERNAL", null, null, null, "{}", Set.of(), Set.of(), false));
    }

    @Override
    public ToolDescriptor register(ToolRegistrationCommand command) {
        ToolRegistrationCommand normalized = normalize(command);
        String encryptedSecret = secretForWrite(normalized);
        jdbc.update("""
                INSERT INTO kb_tool_definition(name,description,risk_level,enabled,type,endpoint,auth_header_name,
                auth_header_value,json_schema,allowed_roles,allowed_dataset_ids,idempotent,update_time)
                VALUES (?,?,?,?,?,?,?, ?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,NOW())
                ON CONFLICT(name) DO UPDATE SET description=EXCLUDED.description,risk_level=EXCLUDED.risk_level,
                enabled=EXCLUDED.enabled,type=EXCLUDED.type,endpoint=EXCLUDED.endpoint,
                auth_header_name=EXCLUDED.auth_header_name,auth_header_value=EXCLUDED.auth_header_value,
                json_schema=EXCLUDED.json_schema,allowed_roles=EXCLUDED.allowed_roles,
                allowed_dataset_ids=EXCLUDED.allowed_dataset_ids,idempotent=EXCLUDED.idempotent,update_time=NOW()
                """, normalized.name(), normalized.description(), normalized.riskLevel(), normalized.enabled(),
                normalized.type(), normalized.endpoint(), normalized.authHeaderName(), encryptedSecret,
                normalized.jsonSchema(), write(normalized.allowedRoles()), write(normalized.allowedDatasetIds()),
                normalized.idempotent());
        return get(normalized.name());
    }

    @Override
    public ToolDescriptor remove(String name) {
        ToolDescriptor removed = getAny(name);
        jdbc.update("DELETE FROM kb_tool_definition WHERE name=?", name);
        return removed;
    }

    @Override
    public ToolDescriptor setEnabled(String name, boolean enabled) {
        ToolDescriptor old = getAny(name);
        jdbc.update("UPDATE kb_tool_definition SET enabled=?,update_time=NOW() WHERE name=?", enabled, old.name());
        return getAny(old.name());
    }

    @Override
    public ToolDescriptor get(String name) {
        ToolDescriptor tool = getAny(name);
        if (!tool.enabled()) throw new IllegalArgumentException("工具不可用: " + name);
        return tool;
    }

    @Override
    public ToolDescriptor getAny(String name) {
        return row(name).descriptor();
    }

    @Override
    public List<ToolDescriptor> list() {
        return jdbc.query(SELECT + " ORDER BY name", this::read).stream()
                .map(DbRow::descriptor).toList();
    }

    @Override
    public List<ToolDescriptor> listEnabled() {
        return list().stream().filter(ToolDescriptor::enabled).toList();
    }

    @Override
    public ToolExecutionSpec resolveForExecution(String name) {
        DbRow row = row(name);
        if (!row.descriptor().enabled()) throw new IllegalArgumentException("工具不可用: " + name);
        return new ToolExecutionSpec(row.descriptor(), secrets.decrypt(row.encryptedSecret()));
    }

    private DbRow row(String name) {
        return jdbc.query(SELECT + " WHERE name=?", this::read, name).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + name));
    }

    private DbRow read(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        String encryptedSecret = rs.getString("auth_header_value");
        ToolDescriptor descriptor = new ToolDescriptor(rs.getString("name"), rs.getString("description"),
                risk(rs.getString("risk_level")), rs.getBoolean("enabled"), type(rs.getString("type")),
                blank(rs.getString("endpoint")), blank(rs.getString("auth_header_name")),
                schema(rs.getString("json_schema")), roles(rs.getString("allowed_roles")),
                datasetIds(rs.getString("allowed_dataset_ids")), rs.getBoolean("idempotent"),
                encryptedSecret != null && !encryptedSecret.isBlank());
        return new DbRow(descriptor, encryptedSecret);
    }

    private String secretForWrite(ToolRegistrationCommand command) {
        if (command.authHeaderValue() != null && !command.authHeaderValue().isBlank()) {
            return secrets.encrypt(command.authHeaderValue());
        }
        return jdbc.query("SELECT auth_header_value FROM kb_tool_definition WHERE name=?",
                (rs, ignored) -> rs.getString(1), command.name()).stream().findFirst().orElse(null);
    }

    private void ensureBuiltin(ToolRegistrationCommand command) {
        jdbc.update("""
                INSERT INTO kb_tool_definition(name,description,risk_level,enabled,type,json_schema,allowed_roles,
                allowed_dataset_ids,idempotent)
                VALUES (?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?)
                ON CONFLICT(name) DO NOTHING
                """, command.name(), command.description(), command.riskLevel(), command.enabled(), command.type(),
                command.jsonSchema(), "[]", "[]", command.idempotent());
    }

    private ToolRegistrationCommand normalize(ToolRegistrationCommand command) {
        validate(command);
        return new ToolRegistrationCommand(command.name().trim(), command.description(), risk(command.riskLevel()),
                command.enabled(), type(command.type()), blank(command.endpoint()), blank(command.authHeaderName()),
                blank(command.authHeaderValue()), schema(command.jsonSchema()), roles(command.allowedRoles()),
                datasetIds(command.allowedDatasetIds()), command.idempotent());
    }

    private void validate(ToolRegistrationCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        if (!command.name().trim().matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("工具名称格式不合法");
        }
        if (command.description() != null && command.description().length() > 500) {
            throw new IllegalArgumentException("工具描述过长");
        }
        if (!Set.of("LOW", "HIGH", "EXTERNAL_SIDE_EFFECT").contains(risk(command.riskLevel()))) {
            throw new IllegalArgumentException("工具风险级别不合法");
        }
        if (!Set.of("INTERNAL", "HTTP").contains(type(command.type()))) {
            throw new IllegalArgumentException("工具类型不合法");
        }
        if ("HTTP".equals(type(command.type())) && blank(command.endpoint()) == null) {
            throw new IllegalArgumentException("HTTP 工具必须配置 endpoint");
        }
        if (command.endpoint() != null) validateEndpoint(command.endpoint());
        if (command.authHeaderName() != null && !command.authHeaderName().isBlank()
                && !command.authHeaderName().trim().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")) {
            throw new IllegalArgumentException("HTTP 认证 Header 名称不合法");
        }
        if (command.authHeaderValue() != null && (command.authHeaderValue().length() > 8192
                || command.authHeaderValue().contains("\r") || command.authHeaderValue().contains("\n"))) {
            throw new IllegalArgumentException("HTTP 认证 Header 值不合法");
        }
        if (command.jsonSchema() != null && command.jsonSchema().length() > 65_536) {
            throw new IllegalArgumentException("工具 JSON Schema 过长");
        }
        if (command.allowedDatasetIds() != null && command.allowedDatasetIds().stream()
                .anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("工具知识库权限不合法");
        }
        if (command.allowedRoles() != null && command.allowedRoles().stream().anyMatch(role -> role == null
                || !role.trim().matches("[A-Za-z0-9_-]{1,80}"))) {
            throw new IllegalArgumentException("工具角色权限不合法");
        }
    }

    private void validateEndpoint(String value) {
        if (value.length() > 2048) throw new IllegalArgumentException("HTTP 工具 endpoint 过长");
        try {
            URI uri = URI.create(value.trim());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !Set.of("http", "https")
                            .contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("HTTP 工具 endpoint 不合法");
            }
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException) throw (IllegalArgumentException) error;
            throw new IllegalArgumentException("HTTP 工具 endpoint 不合法", error);
        }
    }

    private String risk(String value) { return value == null ? "LOW" : value.trim().toUpperCase(Locale.ROOT); }

    private String type(String value) {
        return value == null || value.isBlank() ? "INTERNAL" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private Set<String> roles(Set<String> values) {
        return values == null ? Set.of() : values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private Set<Long> datasetIds(Set<Long> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private Set<String> roles(String value) {
        try {
            return roles(Set.copyOf(json.readValue(value == null ? "[]" : value,
                    new TypeReference<List<String>>() { })));
        } catch (Exception error) {
            throw new IllegalStateException("工具角色权限数据无效，已拒绝加载", error);
        }
    }

    private Set<Long> datasetIds(String value) {
        try {
            return Set.copyOf(json.readValue(value == null ? "[]" : value,
                    new TypeReference<List<Long>>() { }));
        } catch (Exception error) {
            throw new IllegalStateException("工具知识库权限数据无效，已拒绝加载", error);
        }
    }

    private String schema(String value) {
        String text = value == null || value.isBlank() ? "{}" : value.trim();
        try {
            JsonNode node = json.readTree(text);
            if (!node.isObject()) throw new IllegalArgumentException("工具 JSON Schema 必须是对象");
            return text;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("工具 JSON Schema 无法解析");
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value == null ? List.of() : value); }
        catch (Exception error) { throw new IllegalStateException("工具权限序列化失败", error); }
    }

    private record DbRow(ToolDescriptor descriptor, String encryptedSecret) { }
}
