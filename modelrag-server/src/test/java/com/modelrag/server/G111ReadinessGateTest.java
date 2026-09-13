package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.server.eval.BenchmarkEvidence;
import com.modelrag.server.eval.AclIsolationEvidence;
import com.modelrag.server.eval.CutoverReadinessEvaluator;
import com.modelrag.server.eval.EvalCategory;
import com.modelrag.server.eval.EvalParameters;
import com.modelrag.server.eval.EvalReport;
import com.modelrag.server.eval.JsonAclIsolationEvidenceReader;
import com.modelrag.server.eval.JsonBenchmarkEvidenceReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class G111ReadinessGateTest {
    private static final String COMMIT = "d535ebe5146f5f0f89d29042d65ea9281cf527f2";
    private final CutoverReadinessEvaluator evaluator = new CutoverReadinessEvaluator();
    private final CutoverReadinessEvaluator.Thresholds thresholds = new CutoverReadinessEvaluator.Thresholds(
            0, 0, 0, .9, .85, .85, .85, .85, .9, .01, .05, 1.3, 1);

    @Test
    void envIntentCannotReplaceMissingFullBenchmarkEvidence() {
        Map<String, Boolean> gates = evaluator.evaluate(report(1, 100), report(1, 100),
                BenchmarkEvidence.notRun("missing report"), passedAcl(COMMIT), COMMIT, thresholds);
        assertFalse(gates.get("fullScaleBenchmark"));
    }

    @Test
    void completedFullReportWithStaleLeakageFailsTheHardGate() {
        BenchmarkEvidence leaking = fullEvidence(false);
        Map<String, Boolean> gates = evaluator.evaluate(report(1, 100), report(1, 100), leaking,
                passedAcl(COMMIT), COMMIT, thresholds);
        assertFalse(gates.get("noStaleBuildLeakage"));
    }

    @Test
    void allQualityCoveragePerformanceAndBenchmarkGatesCanPass() {
        Map<String, Boolean> gates = evaluator.evaluate(report(1, 100), report(1, 100),
                fullEvidence(true), passedAcl(COMMIT), COMMIT, thresholds);
        assertTrue(gates.values().stream().allMatch(Boolean::booleanValue), gates.toString());
        assertEquals(com.modelrag.server.eval.V2CutoverReadinessReport.READY_FOR_G12,
                evaluator.decision(gates));
    }

    @Test
    void categorySampleCountCannotReplaceRequiredMetricLabels() {
        EvalReport report = report(1, 100);
        Map<String, Map<String, String>> statuses = new LinkedHashMap<>(report.categoryMetricStatus());
        Map<String, String> crossDocument = new LinkedHashMap<>(statuses.get("CROSS_DOCUMENT"));
        crossDocument.put("completeEvidenceRecall", "INSUFFICIENT_LABELS");
        statuses.put("CROSS_DOCUMENT", crossDocument);
        EvalReport unlabeled = new EvalReport(report.total(), report.recallAt5(), report.recallAt20(), report.mrr(),
                report.contextPrecision(), report.contextRecall(), report.answerRelevance(), report.ndcg(),
                report.refusalRate(), report.refusalAccuracy(), report.answerAccuracy(), report.faithfulness(),
                report.parameters(), report.caseResults(), report.badCases(), report.variant(),
                report.canonicalMetrics(), report.metricStatus(), report.telemetry(), report.latencyMs(),
                report.labelCoverage(), report.categoryMetrics(), statuses, report.categorySampleCounts(), null);

        Map<String, Boolean> gates = evaluator.evaluate(report, unlabeled, fullEvidence(true),
                passedAcl(COMMIT), COMMIT, thresholds);

        assertFalse(gates.get("categorySampleCoverage"));
    }

    @Test
    void aclEvidenceMustBePassedAndBoundToTheExpectedCommit() {
        Map<String, Boolean> missing = evaluator.evaluate(report(1, 100), report(1, 100), fullEvidence(true),
                AclIsolationEvidence.notRun("not executed"), COMMIT, thresholds);
        assertFalse(missing.get("noAclLeakage"));
        assertFalse(missing.get("aclEvidenceCommitMatches"));

        Map<String, Boolean> stale = evaluator.evaluate(report(1, 100), report(1, 100), fullEvidence(true),
                passedAcl("older-commit"), COMMIT, thresholds);
        assertTrue(stale.get("noAclLeakage"));
        assertFalse(stale.get("aclEvidenceCommitMatches"));
    }

    @Test
    void benchmarkCommitAndServerJavaMustMatchTheCurrentGate() {
        BenchmarkEvidence evidence = fullEvidence(true);
        Map<String, Boolean> unknownBase = evaluator.evaluate(report(1, 100), report(1, 100), evidence,
                passedAcl(COMMIT), "unknown", thresholds);
        assertFalse(unknownBase.get("benchmarkCommitMatches"));
        assertFalse(unknownBase.get("aclEvidenceCommitMatches"));

        BenchmarkEvidence java17 = fullEvidence(true, COMMIT, "17.0.12");
        Map<String, Boolean> wrongJava = evaluator.evaluate(report(1, 100), report(1, 100), java17,
                passedAcl(COMMIT), COMMIT, thresholds);
        assertFalse(wrongJava.get("serverJava21"));

        for (String invalid : List.of("unknown", "unavailable", "", "java version 21")) {
            Map<String, Boolean> invalidJava = evaluator.evaluate(report(1, 100), report(1, 100),
                    fullEvidence(true, COMMIT, invalid), passedAcl(COMMIT), COMMIT, thresholds);
            assertFalse(invalidJava.get("serverJava21"), invalid);
        }
    }

    @Test
    void jsonReadersKeepAclSeparateAndReadTheServerJvm(@TempDir Path temporary) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path acl = temporary.resolve("acl-isolation.json");
        Files.writeString(acl, """
                {"status":"PASSED","gitCommit":"%s","testName":"G112AclIntegrationTest",\
                 "executedAt":"2026-09-13T00:00:00Z"}
                """.formatted(COMMIT));
        AclIsolationEvidence aclEvidence = new JsonAclIsolationEvidenceReader(json, acl.toString()).read();
        assertTrue(aclEvidence.passed());
        assertEquals(COMMIT, aclEvidence.gitCommit());

        Files.writeString(temporary.resolve("retrieval-scale-full-fixture.json"), """
                {"status":"COMPLETED","mode":"full","benchmarkIdentity":"fixture",\
                 "gitCommit":"%s","fixtureIdentity":"fixture",\
                 "topology":{"sharedV2Index":"modelrag-retrieval-units-v2"},\
                 "runtime":{"serverJavaVersion":"21.0.8"},"correctness":{}}
                """.formatted(COMMIT));
        BenchmarkEvidence benchmark = new JsonBenchmarkEvidenceReader(json, temporary.toString()).read("fixture");
        assertEquals("21.0.8", benchmark.serverJavaVersion());
    }

    private BenchmarkEvidence fullEvidence(boolean noStaleLeakage) {
        return fullEvidence(noStaleLeakage, COMMIT, "21.0.8");
    }

    private BenchmarkEvidence fullEvidence(boolean noStaleLeakage, String commit, String javaVersion) {
        return new BenchmarkEvidence("COMPLETED", "full", commit, "fixture",
                "modelrag-retrieval-units-v2", javaVersion,
                Map.of("activeRetrievalUnits", 1_000_000L, "activeDocuments", 20_001L,
                        "activeBuildCount", 20_001L, "activeBuildFilterLimit", 10_000L,
                        "staleRetrievalUnits", 10_000L, "vectorDimension", 1_024L),
                List.of("semantic-only", "lexical-only", "hybrid", "document-scoped", "broad",
                        "stale-build-exclusion", "high-active-build-count"), List.of(1, 8, 32),
                10, 100, 120, 0, 0, 0, noStaleLeakage,
                true, true, true, "");
    }

    private AclIsolationEvidence passedAcl(String commit) {
        return new AclIsolationEvidence("PASSED", commit, "G112AclIntegrationTest",
                "2026-09-13T00:00:00Z", "");
    }

    private EvalReport report(double quality, double p95) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("documentRecallAt20", quality);
        metrics.put("documentMrr", quality);
        metrics.put("documentNdcg", quality);
        metrics.put("completeEvidenceRecall", quality);
        metrics.put("answerAccuracy", quality);
        metrics.put("refusalAccuracy", quality);
        metrics.put("errorRate", 0D);
        metrics.put("degradedRate", 0D);
        metrics.put("p95LatencyMs", p95);
        Map<String, String> status = new LinkedHashMap<>();
        metrics.keySet().forEach(name -> status.put(name, "COMPARABLE"));
        Map<String, Integer> categories = new LinkedHashMap<>();
        EvalCategory.canonicalNames().forEach(name -> categories.put(name, 1));
        Map<String, Map<String, String>> categoryStatuses = new LinkedHashMap<>();
        EvalCategory.canonicalNames().forEach(name -> categoryStatuses.put(name, Map.of(
                "documentRecallAt20", "COMPARABLE", "nodeRecall", "COMPARABLE",
                "completeEvidenceRecall", "COMPARABLE", "refusalAccuracy", "COMPARABLE")));
        EvalParameters parameters = new EvalParameters(1, "benchmark", 1, 600, 80, 20, .7,
                "DETERMINISTIC", "now", List.of(), 0, 0, "DETERMINISTIC");
        return new EvalReport(10, quality, quality, quality, quality, quality, quality, quality,
                0, quality, quality, 0, parameters, List.of(), List.of(), "V2", metrics, status,
                Map.of(), Map.of(), Map.of(), Map.of(), categoryStatuses, categories, null);
    }
}
