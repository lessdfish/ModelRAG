package com.modelrag.qa.trace;

import java.util.Map;

/** Stable read model for persisted QA audits. */
public record QaAuditView(
        String traceId,
        long datasetId,
        String datasetName,
        Long conversationId,
        String userId,
        String mode,
        String query,
        String answer,
        String citations,
        double confidence,
        boolean refused,
        String createdAt) {

    public static QaAuditView from(Map<String, Object> values) {
        return new QaAuditView(text(values, "traceId"), number(values, "datasetId"), text(values, "datasetName"),
                nullableNumber(values, "conversationId"), text(values, "userId"), text(values, "mode"),
                text(values, "query"), text(values, "answer"), text(values, "citations"),
                decimal(values, "confidence"), flag(values, "refused"), text(values, "createdAt"));
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Map<String, Object> values, String key) {
        Long value = nullableNumber(values, key);
        return value == null ? 0 : value;
    }

    private static Long nullableNumber(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? null : Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static double decimal(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static boolean flag(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }
}
