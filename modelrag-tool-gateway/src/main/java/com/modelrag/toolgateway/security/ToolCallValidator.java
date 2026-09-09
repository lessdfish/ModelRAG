package com.modelrag.toolgateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelrag.toolgateway.catalog.ToolDescriptor;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Authoritative bounded input and schema validation for gateway calls. */
@Component
@Profile("!test")
public class ToolCallValidator {
    private static final Pattern PROMPT_CONTROL = Pattern.compile(
            "(?i)(忽略.{0,12}(之前|所有|系统).{0,12}指令|ignore.{0,20}(previous|all|system).{0,20}instruction|system prompt|developer message|管理员密码|泄露.{0,12}(提示词|系统提示)|<\\|system\\|>|jailbreak|\\bdan\\b)");
    private final ObjectMapper json;

    public ToolCallValidator() {
        this(new ObjectMapper());
    }

    public ToolCallValidator(ObjectMapper json) {
        this.json = json;
    }

    public void validate(ToolDescriptor tool, String params) {
        if (params == null || params.isBlank()) throw new IllegalArgumentException("工具参数不能为空");
        if (params.length() > 4000) throw new IllegalArgumentException("工具参数超过最大长度");
        if (params.indexOf('\0') >= 0) throw new IllegalArgumentException("工具参数包含非法字符");
        if (tool != null && tool.http() && PROMPT_CONTROL.matcher(params).find()) {
            throw new IllegalArgumentException("HTTP 工具参数包含疑似提示词注入指令");
        }
        validateSchema(tool, params);
    }

    private void validateSchema(ToolDescriptor tool, String params) {
        String raw = tool == null ? null : tool.jsonSchema();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) return;
        try {
            JsonNode schema = json.readTree(raw);
            if (!schema.isObject()) throw new IllegalArgumentException("工具 JSON Schema 必须是对象");
            if (schema.has("type") && !"object".equals(schema.path("type").asText())) {
                throw new IllegalArgumentException("工具 JSON Schema 仅支持 object 类型");
            }
            ObjectNode payload = payload(params);
            JsonNode required = schema.path("required");
            if (required.isArray()) for (JsonNode item : required) {
                String name = item.asText();
                if (name.isBlank()) continue;
                JsonNode value = payload.get(name);
                if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
                    throw new IllegalArgumentException("工具参数缺少必填字段: " + name);
                }
            }
            JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                properties.fields().forEachRemaining(entry ->
                        validateType(entry.getKey(), payload.get(entry.getKey()), entry.getValue().path("type").asText("")));
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("工具 JSON Schema 无法解析");
        }
    }

    private ObjectNode payload(String params) throws Exception {
        String trimmed = params.trim();
        if (trimmed.startsWith("{")) {
            JsonNode node = json.readTree(trimmed);
            if (!node.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON 对象");
            return (ObjectNode) node;
        }
        ObjectNode node = json.createObjectNode();
        node.put("query", params);
        node.put("input", params);
        return node;
    }

    private void validateType(String name, JsonNode value, String type) {
        if (value == null || value.isNull() || type.isBlank()) return;
        boolean ok = switch (type) {
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
        if (!ok) throw new IllegalArgumentException("工具参数字段类型不匹配: " + name + " 应为 " + type);
    }
}
