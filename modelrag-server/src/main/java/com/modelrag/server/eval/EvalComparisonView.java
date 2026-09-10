package com.modelrag.server.eval;

import java.util.List;
import java.util.Map;

public record EvalComparisonView(
        String variant,
        int topK,
        int total,
        int answerable,
        double recallAtFinal,
        double recallAtFused,
        double mrr,
        double contextPrecision,
        double contextRecall,
        double ndcg,
        List<String> badCases,
        List<EvalCaseResult> caseResults,
        EvalParameters parameters,
        Map<String, Double> canonicalMetrics,
        Map<String, String> metricStatus,
        Map<String, Long> telemetry,
        Map<String, Double> latencyMs) {

    public EvalComparisonView {
        badCases = badCases == null ? List.of() : List.copyOf(badCases);
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        canonicalMetrics = canonicalMetrics == null ? Map.of() : Map.copyOf(canonicalMetrics);
        metricStatus = metricStatus == null ? Map.of() : Map.copyOf(metricStatus);
        telemetry = telemetry == null ? Map.of() : Map.copyOf(telemetry);
        latencyMs = latencyMs == null ? Map.of() : Map.copyOf(latencyMs);
    }

    /** Compatibility constructor for callers that only need the original comparison shape. */
    public EvalComparisonView(String variant, int topK, int total, int answerable,
            double recallAtFinal, double recallAtFused, double mrr, double contextPrecision,
            double contextRecall, double ndcg, List<String> badCases,
            List<EvalCaseResult> caseResults, EvalParameters parameters) {
        this(variant, topK, total, answerable, recallAtFinal, recallAtFused, mrr, contextPrecision,
                contextRecall, ndcg, badCases, caseResults, parameters, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
