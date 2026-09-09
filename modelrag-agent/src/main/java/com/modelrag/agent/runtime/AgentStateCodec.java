package com.modelrag.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Encodes only the bounded, secret-free recovery representation of AgentState. */
@Service
public class AgentStateCodec {
    private static final int DEFAULT_MAX_BYTES = 1_048_576;
    private final ObjectMapper json;
    private final int maxBytes;

    public AgentStateCodec(ObjectMapper json) {
        this(json, DEFAULT_MAX_BYTES);
    }

    @Autowired
    public AgentStateCodec(ObjectMapper json,
            @Value("${modelrag.agent.runtime.max-checkpoint-bytes:1048576}") int maxBytes) {
        this.json = json;
        this.maxBytes = Math.max(1, maxBytes);
    }

    public String encode(AgentState state) {
        if (state == null) throw new IllegalArgumentException("agent state is required");
        try {
            AgentState safe = sanitize(state);
            String encoded = json.writeValueAsString(safe);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException("agent checkpoint exceeds configured size bound");
            }
            return encoded;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("agent state cannot be serialized", error);
        }
    }

    public String write(AgentState state) { return encode(state); }

    public AgentState decode(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("agent state JSON is empty");
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("agent checkpoint exceeds configured size bound");
        }
        try {
            JsonNode root = json.readTree(value);
            JsonNode version = root == null ? null : root.get("stateVersion");
            if (version == null || !version.canConvertToInt()
                    || version.asInt() != AgentState.CURRENT_STATE_VERSION) {
                throw new IllegalArgumentException("unsupported agent state version");
            }
            return json.treeToValue(root, AgentState.class);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("agent state JSON is invalid", error);
        }
    }

    public AgentState read(String value) { return decode(value); }

    public int maxBytes() { return maxBytes; }

    private AgentState sanitize(AgentState state) {
        Map<String, Object> toolState = sanitizeMap(state.toolState());
        AgentPendingAction pending = state.pendingAction();
        if (pending != null) {
            pending = new AgentPendingAction(pending.actionId(), pending.kind(), pending.actionName(),
                    sanitizeMap(pending.arguments()), pending.idempotencyKey(), pending.requiresApproval(),
                    pending.toolIdempotent(), pending.createdAt());
        }
        return state.toBuilder().toolState(toolState).pendingAction(pending).build();
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String safeKey = key == null ? "" : key;
            if (sensitive(safeKey)) return;
            result.put(safeKey, sanitizeValue(value));
        });
        return result;
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (!sensitive(name)) nested.put(name, sanitizeValue(item));
            });
            return nested;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(item -> { if (values.size() < 32) values.add(sanitizeValue(item)); });
            return values;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    private boolean sensitive(String key) {
        String value = key == null ? "" : key.toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "").replace(" ", "");
        return value.contains("authorization") || value.contains("authheader") || value.contains("apikey")
                || value.contains("secret") || value.contains("password") || value.contains("credential")
                || value.contains("token") || value.contains("chainofthought") || value.contains("reasoning")
                || value.contains("hiddenprompt") || value.equals("prompt");
    }
}
