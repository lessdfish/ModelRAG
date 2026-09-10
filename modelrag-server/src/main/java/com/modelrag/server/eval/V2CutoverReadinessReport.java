package com.modelrag.server.eval;

import java.util.List;
import java.util.Map;

/** Explicit V2 cutover decision; the decision is never inferred from a partial run. */
public record V2CutoverReadinessReport(
        String decision,
        String baseCommit,
        String datasetIdentity,
        String benchmarkIdentity,
        boolean fullScaleBenchmarkRun,
        Map<String, Double> qualityDelta,
        Map<String, Double> v1Quality,
        Map<String, Double> v2Quality,
        Map<String, Map<String, Double>> v1CategoryQuality,
        Map<String, Map<String, Double>> v2CategoryQuality,
        Map<String, Double> configuredThresholds,
        Map<String, Long> benchmarkTopology,
        Map<String, String> metricStatus,
        Map<String, Boolean> hardGates,
        List<String> passReasons,
        List<String> failReasons) {
    public static final String READY_FOR_G12 = "READY_FOR_G12";
    public static final String NOT_READY_FOR_G12 = "NOT_READY_FOR_G12";

    /** Compatibility constructor for the initial readiness response shape. */
    public V2CutoverReadinessReport(String decision, String baseCommit, String datasetIdentity,
            String benchmarkIdentity, boolean fullScaleBenchmarkRun, Map<String, Double> qualityDelta,
            Map<String, String> metricStatus, Map<String, Boolean> hardGates,
            List<String> passReasons, List<String> failReasons) {
        this(decision, baseCommit, datasetIdentity, benchmarkIdentity, fullScaleBenchmarkRun, qualityDelta,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), metricStatus, hardGates,
                passReasons, failReasons);
    }

    public static V2CutoverReadinessReport notReady(String reason) {
        String message = reason == null || reason.isBlank() ? "readiness runner unavailable" : reason;
        return new V2CutoverReadinessReport(NOT_READY_FOR_G12, "unknown", "unknown", "unknown", false,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(message));
    }

    public V2CutoverReadinessReport {
        decision = READY_FOR_G12.equals(decision) ? READY_FOR_G12 : NOT_READY_FOR_G12;
        baseCommit = baseCommit == null ? "unknown" : baseCommit;
        datasetIdentity = datasetIdentity == null ? "unknown" : datasetIdentity;
        benchmarkIdentity = benchmarkIdentity == null ? "unknown" : benchmarkIdentity;
        qualityDelta = qualityDelta == null ? Map.of() : Map.copyOf(qualityDelta);
        v1Quality = v1Quality == null ? Map.of() : Map.copyOf(v1Quality);
        v2Quality = v2Quality == null ? Map.of() : Map.copyOf(v2Quality);
        v1CategoryQuality = v1CategoryQuality == null ? Map.of() : Map.copyOf(v1CategoryQuality);
        v2CategoryQuality = v2CategoryQuality == null ? Map.of() : Map.copyOf(v2CategoryQuality);
        configuredThresholds = configuredThresholds == null ? Map.of() : Map.copyOf(configuredThresholds);
        benchmarkTopology = benchmarkTopology == null ? Map.of() : Map.copyOf(benchmarkTopology);
        metricStatus = metricStatus == null ? Map.of() : Map.copyOf(metricStatus);
        hardGates = hardGates == null ? Map.of() : Map.copyOf(hardGates);
        passReasons = passReasons == null ? List.of() : List.copyOf(passReasons);
        failReasons = failReasons == null ? List.of() : List.copyOf(failReasons);
    }
}
