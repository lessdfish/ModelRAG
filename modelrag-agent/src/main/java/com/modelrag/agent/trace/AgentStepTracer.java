package com.modelrag.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentStepTracer {
    private final List<AgentStepTrace> local = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectMapper json;

    public AgentStepTracer(ObjectProvider<JdbcTemplate> jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public AgentStepTrace record(String executionId, String phase, String message, Map<String, Object> data, long latencyMs) {
        int index = counters.computeIfAbsent(executionId, ignored -> new AtomicInteger()).incrementAndGet();
        String payload = write(data);
        AgentStepTrace trace = new AgentStepTrace(executionId, index, phase, message, payload, status(phase), latencyMs,
                Instant.now().toString());
        local.add(trace);
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.update("""
                    INSERT INTO kb_agent_step_trace(execution_id,step_index,phase,message,data,status,latency_ms)
                    VALUES (?,?,?,?,CAST(? AS jsonb),?,?)
                    """, trace.executionId(), trace.stepIndex(), trace.phase(), trace.message(), trace.data(),
                    trace.status(), trace.latencyMs());
        } catch (Exception ignored) {
        }
        return trace;
    }

    public List<AgentStepTrace> list() {
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            return db.query("""
                    SELECT execution_id,step_index,phase,message,data::text,status,latency_ms,create_time
                    FROM kb_agent_step_trace
                    ORDER BY id DESC
                    LIMIT 500
                    """, (rs, n) -> new AgentStepTrace(rs.getString("execution_id"), rs.getInt("step_index"),
                    rs.getString("phase"), rs.getString("message"), rs.getString("data"), rs.getString("status"),
                    rs.getLong("latency_ms"), rs.getTimestamp("create_time").toInstant().toString()));
        } catch (Exception ignored) {
        }
        java.util.ArrayList<AgentStepTrace> copy = new java.util.ArrayList<>(local);
        java.util.Collections.reverse(copy);
        return copy;
    }

    public List<AgentStepTrace> list(String executionId) {
        return list().stream().filter(step -> executionId.equals(step.executionId()))
                .sorted(java.util.Comparator.comparingInt(AgentStepTrace::stepIndex)).toList();
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String status(String phase) {
        if ("DONE".equals(phase)) return "SUCCESS";
        if ("ERROR".equals(phase)) return "ERROR";
        if ("APPROVAL_REQUIRED".equals(phase)) return "WAITING";
        return "RUNNING";
    }
}
