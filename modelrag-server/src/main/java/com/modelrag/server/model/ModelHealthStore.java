package com.modelrag.server.model;

import com.modelrag.common.model.ModelHealthRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed circuit-breaker and model-candidate registry. */
@Service
@Profile("!test")
public class ModelHealthStore implements ModelHealthRegistry {
    public record Health(String state, int failures, Instant nextProbeAt) {}
    private record Policy(String provider, int priority, boolean enabled, int canaryPercent) {}

    private final JdbcTemplate jdbc;
    private final long openSeconds;
    private final MeterRegistry metrics;

    @Autowired
    public ModelHealthStore(JdbcTemplate jdbc, MeterRegistry metrics) {
        this(jdbc, 30, metrics);
    }

    public ModelHealthStore(JdbcTemplate jdbc) {
        this(jdbc, 30, Metrics.globalRegistry);
    }

    public ModelHealthStore(JdbcTemplate jdbc, long openSeconds) {
        this(jdbc, openSeconds, Metrics.globalRegistry);
    }

    public ModelHealthStore(JdbcTemplate jdbc, long openSeconds, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.openSeconds = openSeconds;
        this.metrics = metrics;
    }

    public boolean available(String name) {
        return available(ModelType.CHAT, name);
    }

    public boolean available(ModelType type, String name) {
        Health current = state(type, name);
        if (!"OPEN".equals(current.state())) return true;
        if (Instant.now().isBefore(current.nextProbeAt())) {
            metrics.counter("modelrag.model.skipped", "model_type", type.name(), "model_name", name,
                    "state", "OPEN").increment();
            return false;
        }
        save(type, name, new Health("HALF_OPEN", current.failures(), Instant.EPOCH));
        metrics.counter("modelrag.model.transitions", "model_type", type.name(), "model_name", name,
                "state", "HALF_OPEN").increment();
        return true;
    }

    @Override
    public boolean available(String modelType, String name) {
        return available(ModelType.valueOf(modelType), name);
    }

    public void success(String name) {
        success(ModelType.CHAT, name);
    }

    public void success(ModelType type, String name) {
        metrics.counter("modelrag.model.calls", "model_type", type.name(), "model_name", name,
                "status", "success").increment();
        save(type, name, new Health("CLOSED", 0, Instant.EPOCH));
    }

    @Override
    public void success(String modelType, String name) {
        success(ModelType.valueOf(modelType), name);
    }

    public void failure(String name) {
        failure(ModelType.CHAT, name);
    }

    public void failure(ModelType type, String name) {
        Health old = state(type, name);
        int failures = old.failures() + 1;
        Health next = "HALF_OPEN".equals(old.state()) || failures >= 3
                ? new Health("OPEN", failures, Instant.now().plusSeconds(openSeconds))
                : new Health("CLOSED", failures, Instant.EPOCH);
        metrics.counter("modelrag.model.calls", "model_type", type.name(), "model_name", name,
                "status", "failure").increment();
        if ("OPEN".equals(next.state()) && !"OPEN".equals(old.state())) {
            metrics.counter("modelrag.model.transitions", "model_type", type.name(), "model_name", name,
                    "state", "OPEN").increment();
        }
        save(type, name, next);
    }

    @Override
    public void failure(String modelType, String name) {
        failure(ModelType.valueOf(modelType), name);
    }

    public Health state(String name) {
        return state(ModelType.CHAT, name);
    }

    public Health state(ModelType type, String name) {
        return jdbc.query("""
                SELECT state, fail_count, next_probe_at
                FROM kb_model_health
                WHERE model_type=? AND model_name=?
                """, (rs, rowNum) -> new Health(rs.getString("state"), rs.getInt("fail_count"),
                instantOrEpoch(rs.getTimestamp("next_probe_at"))), type.name(), name)
                .stream().findFirst().orElse(new Health("CLOSED", 0, Instant.EPOCH));
    }

    public List<ModelHealthView> snapshot() {
        return snapshot(List.of());
    }

    public List<ModelHealthView> snapshot(List<ModelClient> clients) {
        Map<String, ModelHealthView> rows = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT model_type, model_name, provider, state, priority, enabled, canary_percent,
                       fail_count, next_probe_at
                FROM kb_model_health
                ORDER BY model_type, priority DESC, model_name
                """, rs -> {
            while (rs.next()) {
                String type = rs.getString("model_type");
                String name = rs.getString("model_name");
                rows.put(key(type, name), row(type, name, rs.getString("provider"), rs.getString("state"),
                        rs.getInt("priority"), rs.getBoolean("enabled"), rs.getInt("canary_percent"),
                        rs.getInt("fail_count"), instantOrEpoch(rs.getTimestamp("next_probe_at"))));
            }
            return null;
        });
        for (ModelClient client : clients) {
            rows.compute(key(client.type().name(), client.name()), (ignored, current) -> {
                Policy policy = policy(client.type(), client.name());
                Health health = state(client.type(), client.name());
                if (current != null) return current.withAvailableRuntime(true);
                return row(client.type().name(), client.name(), policy.provider(), health.state(), policy.priority(),
                        policy.enabled(), policy.canaryPercent(), health.failures(), health.nextProbeAt())
                        .withAvailableRuntime(true);
            });
        }
        List<ModelHealthView> output = new ArrayList<>(rows.values());
        output.sort(Comparator.comparing(ModelHealthView::modelType)
                .thenComparing(value -> -value.priority())
                .thenComparing(ModelHealthView::modelName));
        return output;
    }

    public ModelHealthView saveCandidate(String type, String name, String provider, int priority,
            boolean enabled, int canaryPercent) {
        ModelType modelType = ModelType.valueOf(type);
        int safeCanary = Math.max(0, Math.min(100, canaryPercent));
        Health health = state(modelType, name);
        jdbc.update("""
                INSERT INTO kb_model_health(model_name, model_type, provider, state, priority, enabled,
                    canary_percent, fail_count, next_probe_at, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT(model_name, model_type) DO UPDATE SET provider=EXCLUDED.provider,
                    priority=EXCLUDED.priority, enabled=EXCLUDED.enabled,
                    canary_percent=EXCLUDED.canary_percent, update_time=NOW()
                """, name, modelType.name(), provider == null || provider.isBlank() ? "local" : provider,
                health.state(), priority, enabled, safeCanary, health.failures(), timestampOrNull(health.nextProbeAt()));
        return readRow(modelType, name);
    }

    public void deleteCandidate(String configId) {
        int separator = configId.indexOf(':');
        if (separator > 0) {
            jdbc.update("DELETE FROM kb_model_health WHERE model_type=? AND model_name=?",
                    configId.substring(0, separator), configId.substring(separator + 1));
        } else {
            jdbc.update("DELETE FROM kb_model_health WHERE model_name=?", configId);
        }
    }

    public boolean enabled(ModelType type, String name) {
        return policy(type, name).enabled();
    }

    public int priority(ModelType type, String name) {
        return policy(type, name).priority();
    }

    public int canaryPercent(ModelType type, String name) {
        return policy(type, name).canaryPercent();
    }

    private ModelHealthView readRow(ModelType type, String name) {
        return jdbc.query("""
                SELECT model_type, model_name, provider, state, priority, enabled, canary_percent,
                       fail_count, next_probe_at
                FROM kb_model_health WHERE model_type=? AND model_name=?
                """, (rs, rowNum) -> row(rs.getString("model_type"), rs.getString("model_name"),
                rs.getString("provider"), rs.getString("state"), rs.getInt("priority"),
                rs.getBoolean("enabled"), rs.getInt("canary_percent"), rs.getInt("fail_count"),
                instantOrEpoch(rs.getTimestamp("next_probe_at"))), type.name(), name)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("模型候选保存失败"));
    }

    private Policy policy(ModelType type, String name) {
        return jdbc.query("""
                SELECT provider, priority, enabled, canary_percent
                FROM kb_model_health WHERE model_type=? AND model_name=?
                """, (rs, rowNum) -> new Policy(rs.getString("provider"), rs.getInt("priority"),
                rs.getBoolean("enabled"), rs.getInt("canary_percent")), type.name(), name)
                .stream().findFirst().orElse(new Policy("local", 0, true, 0));
    }

    private void save(ModelType type, String name, Health value) {
        jdbc.update("""
                INSERT INTO kb_model_health(model_name, model_type, provider, state, fail_count,
                    next_probe_at, update_time)
                VALUES (?, ?, 'local', ?, ?, ?, NOW())
                ON CONFLICT(model_name, model_type) DO UPDATE SET state=EXCLUDED.state,
                    fail_count=EXCLUDED.fail_count, next_probe_at=EXCLUDED.next_probe_at, update_time=NOW()
                """, name, type.name(), value.state(), value.failures(), timestampOrNull(value.nextProbeAt()));
    }

    private ModelHealthView row(String type, String name, String provider, String state, int priority,
            boolean enabled, int canaryPercent, int failures, Instant nextProbeAt) {
        return new ModelHealthView(type, name, provider == null ? "local" : provider, state, priority,
                enabled, canaryPercent, failures,
                nextProbeAt == null || Instant.EPOCH.equals(nextProbeAt) ? null : nextProbeAt, false);
    }

    private static Instant instantOrEpoch(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private static Timestamp timestampOrNull(Instant instant) {
        return instant == null || Instant.EPOCH.equals(instant) ? null : Timestamp.from(instant);
    }

    private static String key(String type, String name) {
        return type + ":" + name;
    }
}
