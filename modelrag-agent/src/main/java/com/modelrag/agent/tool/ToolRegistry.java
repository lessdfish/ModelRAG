package com.modelrag.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ToolRegistry {
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ToolSecretCipher secrets;
    private final ObjectMapper json = new ObjectMapper();

    public ToolRegistry() {
        this(null, new ToolSecretCipher("modelrag-local-tool-secret-change-me"));
    }

    public ToolRegistry(ObjectProvider<JdbcTemplate> jdbc) {
        this(jdbc, new ToolSecretCipher("modelrag-local-tool-secret-change-me"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ToolRegistry(ObjectProvider<JdbcTemplate> jdbc, ToolSecretCipher secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        registerLocal(new ToolDefinition("knowledge_lookup", "检索已授权知识库", "LOW", true));
        registerLocal(new ToolDefinition("destructive_operation", "执行删除或变更操作", "HIGH", true));
        loadDb();
    }

    public ToolDefinition register(ToolDefinition tool) {
        ToolDefinition normalized = registerLocal(tool);
        saveDb(normalized);
        return normalized;
    }

    public ToolDefinition remove(String name) {
        ToolDefinition removed = tools.remove(name);
        if (removed == null) throw new IllegalArgumentException("工具不存在: " + name);
        JdbcTemplate db = db();
        if (db != null) try {
            db.update("DELETE FROM kb_tool_definition WHERE name=?", name);
        } catch (Exception ignored) {
        }
        return removed;
    }

    public ToolDefinition setEnabled(String name, boolean enabled) {
        ToolDefinition old = tools.get(name);
        if (old == null) throw new IllegalArgumentException("工具不存在: " + name);
        return register(new ToolDefinition(old.name(), old.description(), old.riskLevel(), enabled,
                old.type(), old.endpoint(), old.authHeaderName(), old.authHeaderValue(), old.jsonSchema(),
                old.allowedRoles(), old.allowedDatasetIds()));
    }

    public ToolDefinition get(String name) {
        ToolDefinition tool = tools.get(name);
        if (tool == null || !tool.enabled()) throw new IllegalArgumentException("工具不可用: " + name);
        return tool;
    }

    public List<ToolDefinition> list() {
        loadDb();
        return tools.values().stream().sorted(Comparator.comparing(ToolDefinition::name)).toList();
    }

    public List<ToolDefinition> listEnabled() {
        return list().stream().filter(ToolDefinition::enabled).toList();
    }

    private ToolDefinition registerLocal(ToolDefinition tool) {
        validate(tool);
        ToolDefinition normalized = new ToolDefinition(tool.name().trim(), tool.description(),
                risk(tool.riskLevel()), tool.enabled(), type(tool.type()), blank(tool.endpoint()),
                blank(tool.authHeaderName()), blank(tool.authHeaderValue()), blank(tool.jsonSchema()),
                roles(tool.allowedRoles()), datasetIds(tool.allowedDatasetIds()));
        tools.put(normalized.name(), normalized);
        return normalized;
    }

    private void loadDb() {
        JdbcTemplate db = db();
        if (db == null) return;
        try {
            db.query("""
                    SELECT name,description,risk_level,enabled,type,endpoint,auth_header_name,auth_header_value,
                           json_schema::text,allowed_roles::text,allowed_dataset_ids::text
                    FROM kb_tool_definition
                    """, (rs, n) -> registerLocal(new ToolDefinition(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getString(7), secrets.decrypt(rs.getString(8)),
                    rs.getString(9), readRoles(rs.getString(10)), readDatasetIds(rs.getString(11)))));
        } catch (Exception ignored) {
            loadDbLegacy(db);
        }
    }

    private void loadDbLegacy(JdbcTemplate db) {
        try {
            db.query("""
                    SELECT name,description,risk_level,enabled,type,endpoint,auth_header_name,auth_header_value,json_schema::text
                    FROM kb_tool_definition
                    """, (rs, n) -> registerLocal(new ToolDefinition(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getString(7), secrets.decrypt(rs.getString(8)), rs.getString(9))));
        } catch (Exception ignored) {
        }
    }

    private void saveDb(ToolDefinition tool) {
        JdbcTemplate db = db();
        if (db == null) return;
        try {
            db.update("""
                    INSERT INTO kb_tool_definition(name,description,risk_level,enabled,type,endpoint,auth_header_name,auth_header_value,json_schema,allowed_roles,allowed_dataset_ids,update_time)
                    VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),NOW())
                    ON CONFLICT(name) DO UPDATE SET description=EXCLUDED.description,risk_level=EXCLUDED.risk_level,
                        enabled=EXCLUDED.enabled,type=EXCLUDED.type,endpoint=EXCLUDED.endpoint,
                        auth_header_name=EXCLUDED.auth_header_name,auth_header_value=EXCLUDED.auth_header_value,
                        json_schema=EXCLUDED.json_schema,allowed_roles=EXCLUDED.allowed_roles,
                        allowed_dataset_ids=EXCLUDED.allowed_dataset_ids,update_time=NOW()
                    """, tool.name(), tool.description(), tool.riskLevel(), tool.enabled(), tool.type(), tool.endpoint(),
                    tool.authHeaderName(), secrets.encrypt(tool.authHeaderValue()), schema(tool.jsonSchema()),
                    write(tool.allowedRoles()), write(tool.allowedDatasetIds()));
        } catch (Exception ignored) {
        }
    }

    private JdbcTemplate db() {
        return jdbc == null ? null : jdbc.getIfAvailable();
    }

    private void validate(ToolDefinition tool) {
        if (tool == null) throw new IllegalArgumentException("工具不能为空");
        if (tool.name() == null || tool.name().isBlank()) throw new IllegalArgumentException("工具名称不能为空");
        if (!Set.of("LOW", "HIGH").contains(risk(tool.riskLevel()))) throw new IllegalArgumentException("工具风险级别只能是 LOW 或 HIGH");
        String normalizedType = type(tool.type());
        if (!Set.of("INTERNAL", "HTTP").contains(normalizedType)) throw new IllegalArgumentException("工具类型只能是 INTERNAL 或 HTTP");
        if ("HTTP".equals(normalizedType) && (tool.endpoint() == null || tool.endpoint().isBlank())) {
            throw new IllegalArgumentException("HTTP 工具必须配置 endpoint");
        }
        schema(tool.jsonSchema());
    }

    private String risk(String value) {
        return value == null ? "LOW" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String type(String value) {
        return value == null || value.isBlank() ? "INTERNAL" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Set<String> roles(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) result.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private Set<Long> datasetIds(Set<Long> values) {
        if (values == null || values.isEmpty()) return Set.of();
        return Set.copyOf(values);
    }

    private String schema(String value) {
        String text = value == null || value.isBlank() ? "{}" : value.trim();
        try {
            JsonNode node = json.readTree(text);
            if (!node.isObject()) throw new IllegalArgumentException("工具 JSON Schema 必须是对象");
            if (node.has("type") && !"object".equals(node.path("type").asText())) {
                throw new IllegalArgumentException("工具 JSON Schema 仅支持 object 类型");
            }
            return text;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("工具 JSON Schema 无法解析");
        }
    }

    private Set<String> readRoles(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            return roles(Set.copyOf(json.readValue(value, new TypeReference<List<String>>() {})));
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Set<Long> readDatasetIds(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            return Set.copyOf(json.readValue(value, new TypeReference<List<Long>>() {}));
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
