package com.modelrag.server.model;

import com.modelrag.common.model.ModelHealthRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ModelHealthStore implements ModelHealthRegistry {
    public record Health(String state, int failures, Instant nextProbeAt) {}
    private record Policy(String provider, int priority, boolean enabled, int canaryPercent) {}

    private final Map<String, Health> health = new ConcurrentHashMap<>();
    private final Map<String, Policy> policies = new ConcurrentHashMap<>();
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final long openSeconds;
    private final MeterRegistry metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public ModelHealthStore(ObjectProvider<JdbcTemplate> jdbc, MeterRegistry metrics) {
        this(jdbc, 30, metrics);
    }

    public ModelHealthStore(ObjectProvider<JdbcTemplate> jdbc) {
        this(jdbc, 30, Metrics.globalRegistry);
    }

    public ModelHealthStore(ObjectProvider<JdbcTemplate> jdbc, long openSeconds) {
        this(jdbc, openSeconds, Metrics.globalRegistry);
    }

    public ModelHealthStore(ObjectProvider<JdbcTemplate> jdbc, long openSeconds, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.openSeconds = openSeconds;
        this.metrics = metrics;
    }

    public boolean available(String name) {
        return available(ModelType.CHAT, name);
    }

    public boolean available(ModelType type, String name) {
        Health h = state(type, name);
        if (!"OPEN".equals(h.state())) return true;
        if (Instant.now().isBefore(h.nextProbeAt())) { metrics.counter("modelrag.model.skipped","model_type",type.name(),"model_name",name,"state","OPEN").increment(); return false; }
        save(type, name, new Health("HALF_OPEN", h.failures(), Instant.EPOCH));
        metrics.counter("modelrag.model.transitions","model_type",type.name(),"model_name",name,"state","HALF_OPEN").increment();
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
        metrics.counter("modelrag.model.calls","model_type",type.name(),"model_name",name,"status","success").increment();
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
        metrics.counter("modelrag.model.calls","model_type",type.name(),"model_name",name,"status","failure").increment();
        if("OPEN".equals(next.state())&&!"OPEN".equals(old.state()))metrics.counter("modelrag.model.transitions","model_type",type.name(),"model_name",name,"state","OPEN").increment();
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
        return health.getOrDefault(key(type, name), new Health("CLOSED", 0, Instant.EPOCH));
    }

    public List<Map<String, Object>> snapshot() {
        return snapshot(List.of());
    }

    public List<Map<String, Object>> snapshot(List<ModelClient> clients) {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.query("""
                    SELECT model_type,model_name,provider,state,priority,enabled,canary_percent,fail_count,next_probe_at
                    FROM kb_model_health
                    ORDER BY model_type,priority DESC,model_name
                    """, rs -> {
                        while (rs.next()) {
                        Map<String, Object> row = row(rs.getString("model_type"), rs.getString("model_name"),
                                rs.getString("provider"), rs.getString("state"), rs.getInt("priority"),
                                rs.getBoolean("enabled"), rs.getInt("canary_percent"), rs.getInt("fail_count"),
                                rs.getTimestamp("next_probe_at") == null ? null : rs.getTimestamp("next_probe_at").toInstant());
                        rows.put(key(ModelType.valueOf(String.valueOf(row.get("modelType"))), String.valueOf(row.get("modelName"))), row);
                        }
                        return null;
                    });
        } catch (Exception ignored) {
        }
        for (ModelClient client : clients) {
            Health h = state(client.type(), client.name());
            Policy p = policy(client.type(), client.name());
            rows.putIfAbsent(key(client.type(), client.name()), row(client.type().name(), client.name(), p.provider(),
                    h.state(), p.priority(), p.enabled(), p.canaryPercent(), h.failures(),
                    h.nextProbeAt().equals(Instant.EPOCH) ? null : h.nextProbeAt()));
            rows.get(key(client.type(), client.name())).put("availableRuntime", true);
        }
        health.forEach((key, value) -> {
            int split = key.indexOf(':');
            String type = split > 0 ? key.substring(0, split) : ModelType.CHAT.name();
            String name = split > 0 ? key.substring(split + 1) : key;
            Policy p = policy(ModelType.valueOf(type), name);
            rows.putIfAbsent(key, row(type, name, p.provider(), value.state(), p.priority(), p.enabled(),
                    p.canaryPercent(), value.failures(),
                    value.nextProbeAt().equals(Instant.EPOCH) ? null : value.nextProbeAt()));
        });
        policies.forEach((key, policy) -> {
            int split = key.indexOf(':');
            String type = split > 0 ? key.substring(0, split) : ModelType.CHAT.name();
            String name = split > 0 ? key.substring(split + 1) : key;
            Health h = state(ModelType.valueOf(type), name);
            rows.putIfAbsent(key, row(type, name, policy.provider(), h.state(), policy.priority(), policy.enabled(),
                    policy.canaryPercent(), h.failures(), h.nextProbeAt().equals(Instant.EPOCH) ? null : h.nextProbeAt()));
        });
        List<Map<String, Object>> output = new ArrayList<>(rows.values());
        output.sort(java.util.Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("modelType")))
                .thenComparing((Map<String, Object> row) -> -((Number) row.getOrDefault("priority", 0)).intValue())
                .thenComparing(row -> String.valueOf(row.get("modelName"))));
        return output;
    }

    public Map<String, Object> saveCandidate(String type, String name, String provider, int priority, boolean enabled, int canaryPercent) {
        ModelType modelType = ModelType.valueOf(type);
        int safeCanary = Math.max(0, Math.min(100, canaryPercent));
        Policy policy = new Policy(provider == null || provider.isBlank() ? "local" : provider, priority, enabled, safeCanary);
        policies.put(key(modelType, name), policy);
        Health h = state(modelType, name);
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.update("""
                    INSERT INTO kb_model_health(model_name,model_type,provider,state,priority,enabled,canary_percent,fail_count,next_probe_at,update_time)
                    VALUES (?,?,?,?,?,?,?,?,?,NOW())
                    ON CONFLICT(model_name,model_type) DO UPDATE SET provider=EXCLUDED.provider,priority=EXCLUDED.priority,
                        enabled=EXCLUDED.enabled,canary_percent=EXCLUDED.canary_percent,update_time=NOW()
                    """, name, modelType.name(), policy.provider(), h.state(), policy.priority(), policy.enabled(),
                    policy.canaryPercent(), h.failures(), java.sql.Timestamp.from(h.nextProbeAt()));
        } catch (Exception ignored) {
        }
        return row(modelType.name(), name, policy.provider(), h.state(), policy.priority(), policy.enabled(),
                policy.canaryPercent(), h.failures(), h.nextProbeAt().equals(Instant.EPOCH) ? null : h.nextProbeAt());
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

    private Map<String, Object> row(String type, String name, String provider, String state, int priority, boolean enabled,
            int canaryPercent, int failures, Instant nextProbeAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("modelType", type);
        row.put("modelName", name);
        row.put("provider", provider == null ? "local" : provider);
        row.put("state", state);
        row.put("priority", priority);
        row.put("enabled", enabled);
        row.put("canaryPercent", canaryPercent);
        row.put("failures", failures);
        row.put("nextProbeAt", nextProbeAt == null ? null : nextProbeAt.toString());
        row.put("availableRuntime", false);
        return row;
    }

    private Policy policy(ModelType type, String name) {
        String key = key(type, name);
        Policy local = policies.get(key);
        if (local != null) return local;
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            List<Policy> rows = db.query("""
                    SELECT provider,priority,enabled,canary_percent
                    FROM kb_model_health WHERE model_name=? AND model_type=?
                    """, (rs, n) -> new Policy(rs.getString("provider"), rs.getInt("priority"),
                    rs.getBoolean("enabled"), rs.getInt("canary_percent")), name, type.name());
            if (!rows.isEmpty()) {
                policies.put(key, rows.get(0));
                return rows.get(0);
            }
        } catch (Exception ignored) {
        }
        return new Policy("local", 0, true, 0);
    }

    private void save(ModelType type, String name, Health value) {
        health.put(key(type, name), value);
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) try {
            db.update("INSERT INTO kb_model_health(model_name,model_type,provider,state,fail_count,next_probe_at,update_time) VALUES (?,?,?,?,?,?,NOW()) ON CONFLICT(model_name,model_type) DO UPDATE SET state=EXCLUDED.state,fail_count=EXCLUDED.fail_count,next_probe_at=EXCLUDED.next_probe_at,update_time=NOW()",
                    name, type.name(), "local", value.state(), value.failures(), java.sql.Timestamp.from(value.nextProbeAt()));
        } catch (Exception ignored) {
        }
    }

    private String key(ModelType type, String name) {
        return type.name() + ":" + name;
    }
}
