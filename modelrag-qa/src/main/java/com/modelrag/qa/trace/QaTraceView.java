package com.modelrag.qa.trace;

import java.util.Map;

/** Stable read model used by trace, replay and administrative HTTP APIs. */
public record QaTraceView(
        String traceId,
        long datasetId,
        String query,
        String rewrittenQuery,
        String searchQueries,
        String rerankQuery,
        String vectorResults,
        String bm25Results,
        String fusedResults,
        String rerankResults,
        boolean rerankApplied,
        String mmrResults,
        String smallToBigContext,
        String contextChunks,
        String abVariants,
        String finalPrompt,
        String promptContext,
        int contextMaxTokens,
        String answerSource,
        String modelOutput,
        double confidence,
        boolean refused,
        long latencyMs,
        String degradedComponents,
        String retrievalLatencyMs,
        boolean found,
        String answer,
        String citations,
        String stageCounts,
        String diagnosis,
        String failureStage,
        String actionHints,
        String evidencePreview) {

    public static QaTraceView from(Map<String, Object> values) {
        return new QaTraceView(
                text(values, "traceId"), number(values, "datasetId"), text(values, "query"),
                text(values, "rewrittenQuery"), text(values, "searchQueries"), text(values, "rerankQuery"),
                text(values, "vectorResults"), text(values, "bm25Results"), text(values, "fusedResults"),
                text(values, "rerankResults"), flag(values, "rerankApplied"), text(values, "mmrResults"),
                text(values, "smallToBigContext"), text(values, "contextChunks"), text(values, "abVariants"),
                text(values, "finalPrompt"), text(values, "promptContext"), integer(values, "contextMaxTokens"),
                text(values, "answerSource"), text(values, "modelOutput"), decimal(values, "confidence"),
                flag(values, "refused"), number(values, "latencyMs"), text(values, "degradedComponents"),
                text(values, "retrievalLatencyMs"), flag(values, "found"), text(values, "answer"),
                text(values, "citations"), text(values, "stageCounts"), text(values, "diagnosis"),
                text(values, "failureStage"), text(values, "actionHints"), text(values, "evidencePreview"));
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int integer(Map<String, Object> values, String key) {
        return (int) number(values, key);
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
