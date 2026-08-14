package com.modelrag.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed agent step audit; production has no process-local trace fallback. */
@Service
@Profile("!test")
public class AgentStepTracer {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AgentStepTracer(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public AgentStepTrace record(String executionId, String phase, String message, Map<String, Object> data, long latencyMs) {
        String payload = write(data);
        Integer index = jdbc.queryForObject("SELECT COALESCE(MAX(step_index),0)+1 FROM kb_agent_step_trace WHERE execution_id=?",
                Integer.class, executionId);
        int step = index == null ? 1 : index;
        String status = status(phase);
        jdbc.update("""
                INSERT INTO kb_agent_step_trace(execution_id,step_index,phase,message,data,status,latency_ms)
                VALUES (?,?,?,?,CAST(? AS jsonb),?,?)
                """, executionId, step, phase, message, payload, status, Math.max(0, latencyMs));
        return new AgentStepTrace(executionId, step, phase, message, payload, status, latencyMs,
                java.time.Instant.now().toString());
    }

    public List<AgentStepTrace> list() {
        return jdbc.query("""
                SELECT execution_id,step_index,phase,message,data::text,status,latency_ms,create_time
                FROM kb_agent_step_trace ORDER BY id DESC LIMIT 500
                """, (rs, n) -> new AgentStepTrace(rs.getString("execution_id"), rs.getInt("step_index"),
                rs.getString("phase"), rs.getString("message"), rs.getString("data"), rs.getString("status"),
                rs.getLong("latency_ms"), rs.getTimestamp("create_time").toInstant().toString()));
    }

    public List<AgentStepTrace> list(String executionId) {
        return jdbc.query("""
                SELECT execution_id,step_index,phase,message,data::text,status,latency_ms,create_time
                FROM kb_agent_step_trace WHERE execution_id=? ORDER BY step_index
                """, (rs, n) -> new AgentStepTrace(rs.getString("execution_id"), rs.getInt("step_index"),
                rs.getString("phase"), rs.getString("message"), rs.getString("data"), rs.getString("status"),
                rs.getLong("latency_ms"), rs.getTimestamp("create_time").toInstant().toString()), executionId);
    }

    private String write(Map<String, Object> value) {
        try { return json.writeValueAsString(redactMap(value == null ? Map.of() : value)); }
        catch (Exception error) { throw new IllegalArgumentException("Agent trace 数据无法序列化", error); }
    }

    private Map<String, Object> redactMap(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(key, sensitive(key) ? digest(item) : redactNested(item)));
        return result;
    }

    private Object redactNested(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key),
                    sensitive(String.valueOf(key)) ? digest(item) : redactNested(item)));
            return nested;
        }
        if (value instanceof Iterable<?> values) {
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            values.forEach(item -> result.add(redactNested(item)));
            return result;
        }
        return value;
    }

    private boolean sensitive(String key) {
        String value = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        return value.contains("query") || value.contains("prompt") || value.contains("answer")
                || value.contains("content") || value.contains("output") || value.contains("subtask")
                || value.contains("parameter") || value.contains("userid") || value.contains("requester");
    }

    private String digest(Object value) {
        String text = String.valueOf(value == null ? "" : value);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "<redacted length=" + text.length() + " sha256=" + java.util.HexFormat.of().formatHex(hash) + ">";
        } catch (Exception error) {
            throw new IllegalStateException("Agent trace 脱敏失败", error);
        }
    }
    private String status(String phase) {
        if ("DONE".equals(phase)) return "SUCCESS";
        if ("ERROR".equals(phase)) return "ERROR";
        if ("APPROVAL_REQUIRED".equals(phase)) return "WAITING";
        return "RUNNING";
    }
}
