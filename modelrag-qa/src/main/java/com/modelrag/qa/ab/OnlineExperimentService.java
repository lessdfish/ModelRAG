package com.modelrag.qa.ab;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OnlineExperimentService {
    public record Experiment(String id, long datasetId, String name, String description, boolean enabled,
            int trafficPercent, int variantTopK, Instant updateTime) {}

    private final Map<String, Experiment> local = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> localEvents = new ArrayList<>();
    private final ObjectProvider<JdbcTemplate> jdbc;

    public OnlineExperimentService(ObjectProvider<JdbcTemplate> jdbc) {
        this.jdbc = jdbc;
    }

    public List<Experiment> list(long datasetId) {
        JdbcTemplate db = db();
        if (db != null) try {
            return db.query("""
                    SELECT id,dataset_id,name,description,enabled,traffic_percent,variant_top_k,update_time
                    FROM kb_ab_experiment
                    WHERE dataset_id=?
                    ORDER BY update_time DESC,id
                    """, (rs, n) -> new Experiment(rs.getString("id"), rs.getLong("dataset_id"), rs.getString("name"),
                    rs.getString("description"), rs.getBoolean("enabled"), rs.getInt("traffic_percent"),
                    rs.getInt("variant_top_k"), rs.getTimestamp("update_time").toInstant()), datasetId);
        } catch (Exception ignored) {
        }
        return local.values().stream()
                .filter(item -> item.datasetId() == datasetId)
                .sorted(Comparator.comparing(Experiment::updateTime).reversed())
                .toList();
    }

    public List<Experiment> active(long datasetId, String query) {
        return list(datasetId).stream()
                .filter(Experiment::enabled)
                .filter(item -> bucket(item, query))
                .toList();
    }

    public Experiment save(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("实验配置不能为空");
        long datasetId = number(body.get("datasetId"), "datasetId").longValue();
        String id = text(body.getOrDefault("id", "ab-" + datasetId + "-" + System.currentTimeMillis()), "id");
        String name = text(body.getOrDefault("name", "TopK 实验"), "name");
        String description = Objects.toString(body.getOrDefault("description", ""), "");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        int trafficPercent = clamp(number(body.getOrDefault("trafficPercent", 10), "trafficPercent").intValue(), 0, 100);
        int variantTopK = clamp(number(body.getOrDefault("variantTopK", 8), "variantTopK").intValue(), 1, 20);
        Experiment experiment = new Experiment(id, datasetId, name, description, enabled, trafficPercent, variantTopK, Instant.now());
        JdbcTemplate db = db();
        if (db != null) try {
            db.update("""
                    INSERT INTO kb_ab_experiment(id,dataset_id,name,description,enabled,traffic_percent,variant_top_k,update_time)
                    VALUES (?,?,?,?,?,?,?,NOW())
                    ON CONFLICT(id) DO UPDATE SET dataset_id=EXCLUDED.dataset_id,name=EXCLUDED.name,
                        description=EXCLUDED.description,enabled=EXCLUDED.enabled,traffic_percent=EXCLUDED.traffic_percent,
                        variant_top_k=EXCLUDED.variant_top_k,update_time=NOW()
                    """, id, datasetId, name, description, enabled, trafficPercent, variantTopK);
        } catch (Exception ignored) {
        }
        local.put(id, experiment);
        return experiment;
    }

    public Experiment setEnabled(String id, boolean enabled) {
        Experiment old = get(id);
        return save(Map.of("id", old.id(), "datasetId", old.datasetId(), "name", old.name(),
                "description", old.description() == null ? "" : old.description(), "enabled", enabled,
                "trafficPercent", old.trafficPercent(), "variantTopK", old.variantTopK()));
    }

    public void delete(String id) {
        local.remove(id);
        JdbcTemplate db = db();
        if (db != null) try {
            db.update("DELETE FROM kb_ab_experiment WHERE id=?", id);
        } catch (Exception ignored) {
        }
    }

    public void record(String traceId, long datasetId, Map<String, Object> variant, boolean refused, double confidence, long latencyMs) {
        if (variant == null || variant.isEmpty()) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("experimentId", String.valueOf(variant.getOrDefault("experimentId", "")));
        event.put("traceId", traceId);
        event.put("datasetId", datasetId);
        event.put("variant", String.valueOf(variant.getOrDefault("variant", "")));
        event.put("baselineTopK", intValue(variant.get("baselineTopK")));
        event.put("topK", intValue(variant.get("topK")));
        event.put("baselineFinalCount", sizeOf(variant.get("baselineFinalChunkIds")));
        event.put("variantFinalCount", sizeOf(variant.get("finalChunkIds")));
        event.put("deltaCount", sizeOf(variant.get("deltaFinalChunkIds")));
        event.put("refused", refused);
        event.put("confidence", confidence);
        event.put("latencyMs", latencyMs);
        localEvents.add(0, event);
        JdbcTemplate db = db();
        if (db != null) try {
            db.update("""
                    INSERT INTO kb_ab_event(experiment_id,trace_id,dataset_id,variant_name,baseline_top_k,variant_top_k,
                        baseline_final_count,variant_final_count,delta_count,refused,confidence,latency_ms)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, event.get("experimentId"), traceId, datasetId, event.get("variant"), event.get("baselineTopK"),
                    event.get("topK"), event.get("baselineFinalCount"), event.get("variantFinalCount"),
                    event.get("deltaCount"), refused, confidence, latencyMs);
        } catch (Exception ignored) {
        }
    }

    public List<Map<String, Object>> report(long datasetId) {
        JdbcTemplate db = db();
        if (db != null) try {
            return db.query("""
                    SELECT experiment_id,variant_name,COUNT(*) AS samples,
                           AVG(confidence) AS avg_confidence,
                           AVG(CASE WHEN refused THEN 1 ELSE 0 END) AS refusal_rate,
                           AVG(delta_count) AS avg_delta_count,
                           AVG(latency_ms) AS avg_latency_ms,
                           MAX(create_time) AS last_seen
                    FROM kb_ab_event
                    WHERE dataset_id=?
                    GROUP BY experiment_id,variant_name
                    ORDER BY last_seen DESC
                    """, (rs, n) -> Map.<String, Object>of(
                    "experimentId", rs.getString("experiment_id"),
                    "variant", rs.getString("variant_name"),
                    "samples", rs.getLong("samples"),
                    "avgConfidence", rs.getDouble("avg_confidence"),
                    "refusalRate", rs.getDouble("refusal_rate"),
                    "avgDeltaCount", rs.getDouble("avg_delta_count"),
                    "avgLatencyMs", rs.getDouble("avg_latency_ms"),
                    "lastSeen", rs.getTimestamp("last_seen").toInstant().toString()), datasetId);
        } catch (Exception ignored) {
        }
        return aggregateLocal(datasetId);
    }

    private Experiment get(String id) {
        return local.values().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("实验不存在: " + id));
    }

    private boolean bucket(Experiment experiment, String query) {
        if (experiment.trafficPercent() >= 100) return true;
        if (experiment.trafficPercent() <= 0) return false;
        int bucket = Math.floorMod((experiment.id() + ":" + query).hashCode(), 100);
        return bucket < experiment.trafficPercent();
    }

    private List<Map<String, Object>> aggregateLocal(long datasetId) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> event : localEvents) {
            if (((Number) event.getOrDefault("datasetId", -1)).longValue() != datasetId) continue;
            String key = event.get("experimentId") + ":" + event.get("variant");
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
        }
        return groups.entrySet().stream().map(entry -> {
            List<Map<String, Object>> rows = entry.getValue();
            Map<String, Object> first = rows.get(0);
            return Map.<String, Object>of(
                    "experimentId", first.get("experimentId"),
                    "variant", first.get("variant"),
                    "samples", rows.size(),
                    "avgConfidence", average(rows, "confidence"),
                    "refusalRate", averageBoolean(rows, "refused"),
                    "avgDeltaCount", average(rows, "deltaCount"),
                    "avgLatencyMs", average(rows, "latencyMs"),
                    "lastSeen", Instant.now().toString());
        }).toList();
    }

    private double average(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> row.get(key)).filter(Number.class::isInstance)
                .map(Number.class::cast).mapToDouble(Number::doubleValue).average().orElse(0);
    }

    private double averageBoolean(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> Boolean.TRUE.equals(row.get(key)) ? 1d : 0d).mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private int sizeOf(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Number number(Object value, String field) {
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException(field + " 必须是数字");
    }

    private String text(Object value, String field) {
        String text = Objects.toString(value, "").trim();
        if (text.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return text;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private JdbcTemplate db() {
        return jdbc == null ? null : jdbc.getIfAvailable();
    }
}
