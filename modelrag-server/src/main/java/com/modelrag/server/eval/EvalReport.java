package com.modelrag.server.eval;
import java.util.List;

public record EvalReport(
        int total,
        double recallAt5,
        double recallAt20,
        double mrr,
        double contextPrecision,
        double contextRecall,
        double answerRelevance,
        double ndcg,
        double refusalRate,
        double refusalAccuracy,
        double answerAccuracy,
        double faithfulness,
        EvalParameters parameters,
        List<EvalCaseResult> caseResults,
        List<String> badCases,
        String variant,
        java.util.Map<String, Double> canonicalMetrics,
        java.util.Map<String, String> metricStatus,
        java.util.Map<String, Long> telemetry,
        java.util.Map<String, Double> latencyMs,
        java.util.Map<String, Object> labelCoverage,
        java.util.Map<String, java.util.Map<String, Double>> categoryMetrics,
        java.util.Map<String, java.util.Map<String, String>> categoryMetricStatus,
        java.util.Map<String, Integer> categorySampleCounts,
        V2CutoverReadinessReport readiness) {

    public EvalReport {
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        badCases = badCases == null ? List.of() : List.copyOf(badCases);
        variant = variant == null || variant.isBlank() ? "V1" : variant;
        canonicalMetrics = canonicalMetrics == null ? java.util.Map.of() : java.util.Map.copyOf(canonicalMetrics);
        metricStatus = metricStatus == null ? java.util.Map.of() : java.util.Map.copyOf(metricStatus);
        telemetry = telemetry == null ? java.util.Map.of() : java.util.Map.copyOf(telemetry);
        latencyMs = latencyMs == null ? java.util.Map.of() : java.util.Map.copyOf(latencyMs);
        labelCoverage = labelCoverage == null ? java.util.Map.of() : java.util.Map.copyOf(labelCoverage);
        categoryMetrics = categoryMetrics == null ? java.util.Map.of() : java.util.Map.copyOf(categoryMetrics);
        categoryMetricStatus = categoryMetricStatus == null ? java.util.Map.of() : java.util.Map.copyOf(categoryMetricStatus);
        categorySampleCounts = categorySampleCounts == null ? java.util.Map.of() : java.util.Map.copyOf(categorySampleCounts);
    }

    /** Compatibility constructor for persisted V1 reports. */
    public EvalReport(int total, double recallAt5, double recallAt20, double mrr, double contextPrecision,
            double contextRecall, double answerRelevance, double ndcg, double refusalRate,
            double refusalAccuracy, double answerAccuracy, double faithfulness, EvalParameters parameters,
            List<EvalCaseResult> caseResults, List<String> badCases) {
        this(total, recallAt5, recallAt20, mrr, contextPrecision, contextRecall, answerRelevance, ndcg,
                refusalRate, refusalAccuracy, answerAccuracy, faithfulness, parameters, caseResults, badCases,
                "V1", java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), null);
    }
}
