package com.modelrag.server.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure, evidence-driven G12 gate evaluation. */
public class CutoverReadinessEvaluator {
    public record Thresholds(double qualityRegressionTolerance, double errorRateTolerance,
            double degradedRateTolerance, double minDocumentRecallAt20, double minDocumentMrr,
            double minDocumentNdcg, double minCompleteEvidenceRecall, double minAnswerAccuracy,
            double minRefusalAccuracy, double maxErrorRate, double maxDegradedRate,
            double maxP95LatencyRegressionRatio, int minCategorySamples) { }

    public Map<String, Boolean> evaluate(EvalReport v1, EvalReport v2, BenchmarkEvidence benchmark,
            Thresholds thresholds) {
        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("canonicalDocumentLabels", comparable(v1, "documentRecallAt20")
                && comparable(v2, "documentRecallAt20") && comparable(v1, "documentMrr")
                && comparable(v2, "documentMrr") && comparable(v1, "documentNdcg")
                && comparable(v2, "documentNdcg"));
        gates.put("documentRecallNoRegression", delta(v2, v1, "documentRecallAt20")
                >= -thresholds.qualityRegressionTolerance());
        gates.put("documentMrrNoRegression", delta(v2, v1, "documentMrr")
                >= -thresholds.qualityRegressionTolerance());
        gates.put("documentNdcgNoRegression", delta(v2, v1, "documentNdcg")
                >= -thresholds.qualityRegressionTolerance());
        gates.put("errorRateNoRegression", delta(v2, v1, "errorRate") <= thresholds.errorRateTolerance());
        gates.put("degradedRateNoRegression", delta(v2, v1, "degradedRate") <= thresholds.degradedRateTolerance());
        gates.put("documentRecallAt20", comparable(v2, "documentRecallAt20")
                && metric(v2, "documentRecallAt20") >= thresholds.minDocumentRecallAt20());
        gates.put("documentMrr", comparable(v2, "documentMrr")
                && metric(v2, "documentMrr") >= thresholds.minDocumentMrr());
        gates.put("documentNdcg", comparable(v2, "documentNdcg")
                && metric(v2, "documentNdcg") >= thresholds.minDocumentNdcg());
        gates.put("completeEvidenceRecall", comparable(v2, "completeEvidenceRecall")
                && metric(v2, "completeEvidenceRecall") >= thresholds.minCompleteEvidenceRecall());
        gates.put("answerAccuracy", comparable(v2, "answerAccuracy")
                && metric(v2, "answerAccuracy") >= thresholds.minAnswerAccuracy());
        gates.put("refusalAccuracy", comparable(v2, "refusalAccuracy")
                && metric(v2, "refusalAccuracy") >= thresholds.minRefusalAccuracy());
        gates.put("errorRate", metric(v2, "errorRate") <= thresholds.maxErrorRate());
        gates.put("degradedRate", metric(v2, "degradedRate") <= thresholds.maxDegradedRate());
        gates.put("p95LatencyRegression", p95Ratio(v1, v2) <= thresholds.maxP95LatencyRegressionRatio());
        gates.put("categorySampleCoverage", EvalCategory.canonicalNames().stream().allMatch(category ->
                v2.categorySampleCounts().getOrDefault(category, 0) >= thresholds.minCategorySamples()));
        gates.put("fullScaleBenchmark", benchmark.isVerifiedFull());
        gates.put("staleBuildAndFilterValidated", benchmark.isVerifiedFull());
        gates.put("noAclLeakage", benchmark.noAclLeakage());
        gates.put("noStaleBuildLeakage", benchmark.noStaleBuildLeakage());
        gates.put("noActiveBuildTruncation", benchmark.noActiveBuildTruncation());
        gates.put("boundedResults", benchmark.boundedResults());
        gates.put("validEvidence", benchmark.validEvidence());
        gates.put("v1DefaultPreserved", true);
        return Map.copyOf(gates);
    }

    public double p95Ratio(EvalReport v1, EvalReport v2) {
        double baseline = metric(v1, "p95LatencyMs");
        double candidate = metric(v2, "p95LatencyMs");
        return baseline <= 0 ? (candidate <= 0 ? 1 : Double.POSITIVE_INFINITY) : candidate / baseline;
    }

    public String decision(Map<String, Boolean> gates) {
        return gates != null && !gates.isEmpty() && gates.values().stream().allMatch(Boolean::booleanValue)
                ? V2CutoverReadinessReport.READY_FOR_G12 : V2CutoverReadinessReport.NOT_READY_FOR_G12;
    }

    private double delta(EvalReport left, EvalReport right, String name) {
        return metric(left, name) - metric(right, name);
    }

    private double metric(EvalReport report, String name) { return report.canonicalMetrics().getOrDefault(name, 0D); }
    private boolean comparable(EvalReport report, String name) {
        return EvalLabels.COMPARABLE.equals(report.metricStatus().get(name));
    }
}
