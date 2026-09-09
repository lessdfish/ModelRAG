package com.modelrag.toolgateway.trace;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed tool audit; plaintext inputs, outputs, and credentials are never stored. */
@Service
@Profile("!test")
public class ToolCallTracer {
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    public ToolCallTracer(JdbcTemplate jdbc) {
        this(jdbc, Metrics.globalRegistry);
    }

    @Autowired
    public ToolCallTracer(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public void record(ToolCallTrace trace) {
        metrics.counter("modelrag.tool.calls", "tool", trace.toolName(), "status", trace.success() ? "success" : "failure").increment();
        metrics.timer("modelrag.tool.latency", "tool", trace.toolName(), "status", trace.success() ? "success" : "failure")
                .record(Math.max(0, trace.latencyMs()), TimeUnit.MILLISECONDS);
        jdbc.update("""
                INSERT INTO kb_tool_trace(trace_id,tool_name,input_params,output_result,success,error_msg,latency_ms)
                VALUES (?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?)
                """, trace.traceId(), trace.toolName(), redacted(trace.params()), redacted(trace.output()), trace.success(),
                trace.error() == null ? null : "工具调用失败 [" + hash(trace.error()) + "]", trace.latencyMs());
    }

    public List<ToolCallTrace> list() {
        return jdbc.query("""
                SELECT trace_id,tool_name,input_params::text,output_result::text,success,error_msg,latency_ms
                FROM kb_tool_trace ORDER BY id DESC LIMIT 200
                """, (rs, n) -> new ToolCallTrace(rs.getString("trace_id"), rs.getString("tool_name"),
                rs.getString("input_params"), rs.getString("output_result"), rs.getBoolean("success"),
                rs.getString("error_msg"), rs.getLong("latency_ms")));
    }

    private String redacted(String value) {
        String safe = value == null ? "" : value;
        return "{\"redacted\":true,\"length\":" + safe.length() + ",\"sha256\":\"" + hash(safe) + "\"}";
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("工具 trace 脱敏失败", error);
        }
    }
}
