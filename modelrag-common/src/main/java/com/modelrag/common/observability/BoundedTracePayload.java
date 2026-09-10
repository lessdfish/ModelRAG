package com.modelrag.common.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Redacts and bounds trace payloads before they reach a database or log sink. */
public final class BoundedTracePayload {
    public static final int MAX_JSON_CHARS = 8192;
    public static final int MAX_EXCERPT_CHARS = 2000;
    private static final Set<String> SAFE_KEYS = Set.of(
            "actionType", "mode", "stage", "channel", "status", "profile", "topK", "candidateCount",
            "evidenceCount", "selectedCount", "navigationActions", "degraded", "degradedComponents",
            "timeoutMs", "activeBuildCount", "activeBuildFilterLimit", "scopeOverflow", "rank", "count",
            "durationMs", "rerankApplied", "resultCount", "documentScoped", "semanticCount", "lexicalCount",
            "fusedCount", "itemCount", "newEvidenceCount", "totalActions", "totalEvidence", "actionId",
            "reason", "refused");

    private BoundedTracePayload() { }

    public static Map<String, Object> safeSummary(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) return result;
        source.forEach((key, value) -> {
            if (result.size() >= 32 || key == null || !SAFE_KEYS.contains(key)) return;
            Object safe = value(value, 0);
            if (safe != null) result.put(key, safe);
        });
        return result;
    }

    public static Map<String, Object> safeLocator(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) return result;
        source.forEach((key, value) -> {
            if (result.size() >= 12 || key == null || sensitive(key)) return;
            Object safe = value(value, 0);
            if (safe != null) result.put(key, safe);
        });
        return result;
    }

    public static String safeExcerpt(String value) {
        if (value == null || value.isBlank()) return "";
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[-_ ]?key|secret|password|credential)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .trim();
        return clean.length() <= MAX_EXCERPT_CHARS ? clean : clean.substring(0, MAX_EXCERPT_CHARS) + "…";
    }

    public static List<String> safeExcerptList(Collection<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        return source.stream().filter(value -> value != null && !value.isBlank())
                .map(BoundedTracePayload::safeExcerpt).limit(16).toList();
    }

    public static String json(ObjectMapper mapper, Map<String, ?> source) {
        try {
            String text = mapper.writeValueAsString(safeSummary(source));
            return text.length() <= MAX_JSON_CHARS ? text : "{\"truncated\":true}";
        } catch (Exception ignored) {
            return "{}";
        }
    }

    public static String jsonList(ObjectMapper mapper, Collection<String> source) {
        try {
            List<String> values = source == null ? List.of() : source.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(BoundedTracePayload::safeExcerpt).limit(32).toList();
            String text = mapper.writeValueAsString(values);
            return text.length() <= MAX_JSON_CHARS ? text : "[]";
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static Object value(Object source, int depth) {
        if (source == null || depth > 2) return null;
        if (source instanceof Number || source instanceof Boolean) return source;
        if (source instanceof CharSequence text) return safeExcerpt(text.toString()).substring(0,
                Math.min(256, safeExcerpt(text.toString()).length()));
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (result.size() < 16 && key != null && !sensitive(String.valueOf(key))) {
                    Object safe = value(item, depth + 1);
                    if (safe != null) result.put(String.valueOf(key).substring(0,
                            Math.min(64, String.valueOf(key).length())), safe);
                }
            });
            return result;
        }
        if (source instanceof Collection<?> values) {
            List<Object> result = new ArrayList<>();
            for (Object item : values) {
                if (result.size() >= 16) break;
                Object safe = value(item, depth + 1);
                if (safe != null) result.add(safe);
            }
            return result;
        }
        return null;
    }

    private static boolean sensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("vector") || normalized.contains("embedding") || normalized.contains("prompt")
                || normalized.contains("secret") || normalized.contains("password") || normalized.contains("credential")
                || normalized.contains("authorization") || normalized.contains("token") || normalized.contains("agentstate")
                || normalized.equals("state") || normalized.contains("modeloutput") || normalized.equals("output")
                || normalized.contains("raw") || normalized.equals("content") || normalized.equals("text")
                || normalized.equals("query");
    }
}
