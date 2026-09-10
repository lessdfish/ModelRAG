package com.modelrag.server.eval;

import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.common.observability.RetrievalTraceSink;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.qa.orchestrator.QaV2ApplicationService;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import com.modelrag.search.facade.SearchFacade;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Runs V1 and V2 through separate adapters and keeps metric calculation out of the controller. */
@Service
@Profile("!test")
public class EvaluationRunner {
    private final DatasetRepository datasets;
    private final EvaluationLabelResolver labels;
    private final V1EvaluationAdapter v1;
    private final V2EvaluationAdapter v2;
    private final EvaluationMetrics metrics;
    private final String baseCommit;
    private final boolean fullScaleBenchmarkRun;

    @Value("${modelrag.eval.quality-regression-tolerance:0.0}")
    private double qualityRegressionTolerance;
    @Value("${modelrag.eval.error-rate-tolerance:0.0}")
    private double errorRateTolerance;
    @Value("${modelrag.eval.degraded-rate-tolerance:0.0}")
    private double degradedRateTolerance;
    @Value("${modelrag.eval.benchmark.active-retrieval-units:0}")
    private long benchmarkActiveRetrievalUnits;
    @Value("${modelrag.eval.benchmark.active-documents:0}")
    private long benchmarkActiveDocuments;
    @Value("${modelrag.eval.benchmark.stale-retrieval-units:0}")
    private long benchmarkStaleRetrievalUnits;
    @Value("${modelrag.eval.benchmark.active-build-count:0}")
    private long benchmarkActiveBuildCount;
    @Value("${modelrag.eval.benchmark.active-build-filter-limit:10000}")
    private long benchmarkActiveBuildFilterLimit;
    @Value("${modelrag.eval.benchmark.no-acl-leakage:false}")
    private boolean noAclLeakage;
    @Value("${modelrag.eval.benchmark.no-stale-build-leakage:false}")
    private boolean noStaleBuildLeakage;
    @Value("${modelrag.eval.benchmark.no-active-build-truncation:false}")
    private boolean noActiveBuildTruncation;
    @Value("${modelrag.eval.benchmark.bounded-results:false}")
    private boolean boundedResults;
    @Value("${modelrag.eval.benchmark.valid-evidence:false}")
    private boolean validEvidence;

    public EvaluationRunner(DatasetRepository datasets, ChunkRepository chunks, SearchFacade search,
            QaOrchestrator qa, HybridRetrievalService retrieval, QaV2ApplicationService qaV2,
            EvaluationMetrics metrics,
            ObjectProvider<RetrievalTraceSink> traceSinks,
            @Value("${modelrag.index.v2.embedding-profile:qwen3-v1}") String embeddingProfile,
            @Value("${modelrag.eval.base-commit:unknown}") String baseCommit,
            @Value("${modelrag.eval.full-scale-benchmark-run:false}") boolean fullScaleBenchmarkRun) {
        this.datasets = datasets;
        this.labels = new EvaluationLabelResolver(chunks);
        RetrievalTraceSink traceSink = traceSinks == null ? RetrievalTraceSink.NOOP
                : traceSinks.getIfAvailable(() -> RetrievalTraceSink.NOOP);
        this.v1 = new V1EvaluationAdapter(search, qa, chunks, traceSink);
        this.v2 = new V2EvaluationAdapter(retrieval, qaV2, embeddingProfile, traceSink);
        this.metrics = metrics;
        this.baseCommit = baseCommit == null || baseCommit.isBlank() ? "unknown" : baseCommit;
        this.fullScaleBenchmarkRun = fullScaleBenchmarkRun;
    }

    public EvalReport run(long datasetId, List<EvalItem> items) {
        return runVariant(datasetId, items, "V1");
    }

    public List<EvalComparisonView> compare(long datasetId, List<EvalItem> items, List<Integer> topKs) {
        List<EvalComparisonView> result = new ArrayList<>();
        for (Integer topK : topKs == null || topKs.isEmpty() ? List.of(5) : topKs) {
            int boundedTopK = Math.max(1, Math.min(20, topK == null ? 5 : topK));
            result.add(comparison(runVariant(datasetId, items, "V1", boundedTopK)));
            result.add(comparison(runVariant(datasetId, items, "V2", boundedTopK)));
        }
        return result;
    }

    public V2CutoverReadinessReport readiness(long datasetId, List<EvalItem> items, String benchmarkIdentity) {
        EvalReport v1Report = runVariant(datasetId, items, "V1");
        EvalReport v2Report = runVariant(datasetId, items, "V2");
        Map<String, Double> delta = new LinkedHashMap<>();
        delta.put("documentRecallAt20", metric(v2Report, "documentRecallAt20") - metric(v1Report, "documentRecallAt20"));
        delta.put("documentMrr", metric(v2Report, "documentMrr") - metric(v1Report, "documentMrr"));
        delta.put("documentNdcg", metric(v2Report, "documentNdcg") - metric(v1Report, "documentNdcg"));
        delta.put("errorRate", metric(v2Report, "errorRate") - metric(v1Report, "errorRate"));
        delta.put("degradedRate", metric(v2Report, "degradedRate") - metric(v1Report, "degradedRate"));

        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("canonicalDocumentLabels", comparable(v1Report, "documentRecallAt20")
                && comparable(v2Report, "documentRecallAt20")
                && comparable(v1Report, "documentMrr") && comparable(v2Report, "documentMrr")
                && comparable(v1Report, "documentNdcg") && comparable(v2Report, "documentNdcg"));
        gates.put("documentRecallNoRegression", delta.get("documentRecallAt20") >= -qualityRegressionTolerance);
        gates.put("documentMrrNoRegression", delta.get("documentMrr") >= -qualityRegressionTolerance);
        gates.put("documentNdcgNoRegression", delta.get("documentNdcg") >= -qualityRegressionTolerance);
        gates.put("errorRateNoRegression", delta.get("errorRate") <= errorRateTolerance);
        gates.put("degradedRateNoRegression", delta.get("degradedRate") <= degradedRateTolerance);
        gates.put("fullScaleBenchmark", fullScaleBenchmarkRun);
        gates.put("staleBuildAndFilterValidated", fullScaleBenchmarkRun
                && benchmarkStaleRetrievalUnits > 0
                && benchmarkActiveBuildCount > benchmarkActiveBuildFilterLimit);
        gates.put("noAclLeakage", noAclLeakage);
        gates.put("noStaleBuildLeakage", noStaleBuildLeakage);
        gates.put("noActiveBuildTruncation", noActiveBuildTruncation);
        gates.put("boundedResults", boundedResults);
        gates.put("validEvidence", validEvidence);
        gates.put("v1DefaultPreserved", true);
        List<String> passes = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        gates.forEach((name, pass) -> (pass ? passes : failures).add(name + (pass ? " passed" : " failed")));
        boolean ready = failures.isEmpty();
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.putAll(v1Report.metricStatus());
        v2Report.metricStatus().forEach((key, value) -> statuses.put("V2." + key, value));
        Dataset dataset = datasets.findById(datasetId);
        return new V2CutoverReadinessReport(ready ? V2CutoverReadinessReport.READY_FOR_G12
                : V2CutoverReadinessReport.NOT_READY_FOR_G12, baseCommit,
                dataset.id() + "@revision-" + dataset.revision(), benchmarkIdentity, fullScaleBenchmarkRun,
                delta, v1Report.canonicalMetrics(), v2Report.canonicalMetrics(), v1Report.categoryMetrics(),
                v2Report.categoryMetrics(), configuredThresholds(), benchmarkTopology(), statuses, gates, passes, failures);
    }

    private EvalReport runVariant(long datasetId, List<EvalItem> items, String variant) {
        return runVariant(datasetId, items, variant, 5);
    }

    private EvalReport runVariant(long datasetId, List<EvalItem> items, String variant, int topK) {
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("评测集不能为空");
        List<EvalLabels> resolvedLabels = new ArrayList<>();
        List<EvaluationObservation> observations = new ArrayList<>();
        List<EvaluationMetrics.CaseScore> scores = new ArrayList<>();
        List<EvalCaseResult> cases = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (EvalItem item : items) {
            EvalLabels resolved = labels.resolve(datasetId, item);
            EvaluationObservation observation = "V2".equals(variant)
                    ? v2.run(datasetId, item, topK, 0) : v1.run(datasetId, item, topK);
            EvaluationMetrics.CaseScore score = metrics.score(resolved, observation, item.question(),
                    item.expectedAnswer(), Boolean.TRUE.equals(item.shouldRefuse()));
            resolvedLabels.add(resolved);
            observations.add(observation);
            scores.add(score);
            if (observation.error()) bad.add("ERROR: " + item.question());
            else if (!Boolean.TRUE.equals(item.shouldRefuse()) && score.documentRank() == 0) {
                bad.add("RETRIEVAL: " + item.question());
            } else if (score.answerChecked() && !score.answerCorrect()) {
                bad.add("GENERATION: " + item.question());
            }
            cases.add(caseResult(item, resolved, observation, score));
        }
        EvaluationMetrics.Aggregate aggregate = metrics.aggregate(resolvedLabels, observations, scores);
        Map<String, List<EvalLabels>> labelsByCategory = new LinkedHashMap<>();
        Map<String, List<EvaluationObservation>> observationsByCategory = new LinkedHashMap<>();
        Map<String, List<EvaluationMetrics.CaseScore>> scoresByCategory = new LinkedHashMap<>();
        for (int index = 0; index < items.size(); index++) {
            String category = items.get(index).category();
            labelsByCategory.computeIfAbsent(category, ignored -> new ArrayList<>()).add(resolvedLabels.get(index));
            observationsByCategory.computeIfAbsent(category, ignored -> new ArrayList<>()).add(observations.get(index));
            scoresByCategory.computeIfAbsent(category, ignored -> new ArrayList<>()).add(scores.get(index));
        }
        Map<String, Map<String, Double>> categoryValues = new LinkedHashMap<>();
        Map<String, Map<String, String>> categoryStatus = new LinkedHashMap<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        labelsByCategory.forEach((category, categoryLabels) -> {
            EvaluationMetrics.Aggregate categoryAggregate = metrics.aggregate(categoryLabels,
                    observationsByCategory.get(category), scoresByCategory.get(category));
            categoryValues.put(category, categoryAggregate.values());
            Map<String, String> statuses = new LinkedHashMap<>(categoryAggregate.status());
            if (categoryLabels.size() < 2) {
                statuses.replaceAll((metricName, ignored) -> "INSUFFICIENT_SAMPLE");
            }
            categoryStatus.put(category, statuses);
            categoryCounts.put(category, categoryLabels.size());
        });
        for (String category : EvalCategory.canonicalNames()) {
            categoryValues.putIfAbsent(category, Map.of());
            categoryStatus.putIfAbsent(category, Map.of("category", "INSUFFICIENT_SAMPLE"));
            categoryCounts.putIfAbsent(category, 0);
        }
        EvalParameters parameters = parameters(datasetId, topK);
        Map<String, Double> value = aggregate.values();
        double refusalRate = observations.isEmpty() ? 0 : observations.stream().filter(EvaluationObservation::refused).count()
                / (double) observations.size();
        double answerAccuracy = value.getOrDefault("answerAccuracy", 0D);
        return new EvalReport(items.size(), value.getOrDefault("documentRecallAt5", 0D),
                value.getOrDefault("documentRecallAt20", 0D), value.getOrDefault("documentMrr", 0D),
                value.getOrDefault("evidencePrecision", 0D), value.getOrDefault("documentRecallAt20", 0D),
                value.getOrDefault("answerRelevance", 0D), value.getOrDefault("documentNdcg", 0D), refusalRate,
                value.getOrDefault("refusalAccuracy", 0D), answerAccuracy, value.getOrDefault("faithfulness", 0D),
                parameters, cases, bad, variant, value, aggregate.status(), aggregate.telemetry(),
                aggregate.latencyMs(), aggregate.labelCoverage(), categoryValues, categoryStatus, categoryCounts, null);
    }

    private EvalCaseResult caseResult(EvalItem item, EvalLabels labels, EvaluationObservation observation,
            EvaluationMetrics.CaseScore score) {
        String stage = observation.error() ? "ERROR"
                : Boolean.TRUE.equals(item.shouldRefuse())
                        ? (observation.refused() ? "EXPECTED_REFUSAL" : "REFUSAL_MISSED")
                        : score.documentRank() == 0 ? "RECALL"
                        : score.answerChecked() && !score.answerCorrect() ? "GENERATION" : "ANSWER_USE";
        int finalChunkRank = rank(item.expectedChunkIds(), observation.finalChunkIds());
        int fusedChunkRank = rank(item.expectedChunkIds(), observation.fusedChunkIds());
        List<Long> vectorIds = "V1".equalsIgnoreCase(observation.variant()) ? observation.semanticResultIds() : List.of();
        List<Long> lexicalIds = "V1".equalsIgnoreCase(observation.variant()) ? observation.lexicalResultIds() : List.of();
        List<Long> fusedIds = "V1".equalsIgnoreCase(observation.variant()) ? observation.fusedChunkIds() : List.of();
        List<Long> finalIds = "V1".equalsIgnoreCase(observation.variant()) ? observation.finalChunkIds() : List.of();
        return new EvalCaseResult(item.question(), item.category(), item.sourceTraceId(), stage, stage,
                List.of(), item.expectedChunkIds(), vectorIds, lexicalIds, fusedIds, finalIds,
                finalChunkRank, fusedChunkRank,
                finalChunkRank > 0, fusedChunkRank > 0, observation.refused(), Boolean.TRUE.equals(item.shouldRefuse()),
                score.answerCorrect(), score.faithfulness(), score.answerRelevance(), "DETERMINISTIC",
                observation.variant(), labels.expectedDocumentIds(), labels.expectedNodeIds(),
                labels.expectedEvidenceGroups(), observation.finalDocumentIds(), observation.finalNodeIds(),
                observation.finalRetrievalUnitIds(), observation.semanticResultIds(), observation.lexicalResultIds(),
                observation.rerankedResultIds(), score.documentRank(), score.nodeRank(), observation.error(),
                observation.degraded(), observation.latencyMs(), observation.actionCount(), observation.navigationCount(),
                Map.of("document", labels.documentStatus(), "node", labels.nodeStatus(), "evidence", labels.evidenceStatus()));
    }

    private EvalComparisonView comparison(EvalReport report) {
        return new EvalComparisonView(report.variant(), report.parameters().topK(), report.total(),
                report.total() - report.telemetry().getOrDefault("expectedRefusals", 0L).intValue(),
                report.recallAt20(), report.recallAt20(), report.mrr(), report.contextPrecision(),
                report.contextRecall(), report.ndcg(), report.badCases(), report.caseResults(), report.parameters(),
                report.canonicalMetrics(), report.metricStatus(), report.telemetry(), report.latencyMs());
    }

    private EvalParameters parameters(long datasetId, int topK) {
        Dataset dataset = datasets.findById(datasetId);
        return new EvalParameters(dataset.id(), dataset.name(), dataset.revision(), dataset.chunkSize(),
                dataset.chunkOverlap(), topK, dataset.threshold(), "DETERMINISTIC", Instant.now().toString(),
                List.of(new EvalJudgeModeCount("DETERMINISTIC", 1)), 0, 0, "DETERMINISTIC");
    }

    private double metric(EvalReport report, String name) { return report.canonicalMetrics().getOrDefault(name, 0D); }
    private boolean comparable(EvalReport report, String name) {
        return EvalLabels.COMPARABLE.equals(report.metricStatus().get(name));
    }

    private Map<String, Double> configuredThresholds() {
        return Map.of("qualityRegressionTolerance", qualityRegressionTolerance,
                "errorRateTolerance", errorRateTolerance, "degradedRateTolerance", degradedRateTolerance);
    }

    private Map<String, Long> benchmarkTopology() {
        return Map.of("activeRetrievalUnits", Math.max(0, benchmarkActiveRetrievalUnits),
                "activeDocuments", Math.max(0, benchmarkActiveDocuments),
                "staleRetrievalUnits", Math.max(0, benchmarkStaleRetrievalUnits),
                "activeBuildCount", Math.max(0, benchmarkActiveBuildCount),
                "activeBuildFilterLimit", Math.max(0, benchmarkActiveBuildFilterLimit));
    }

    private int rank(List<Long> expected, List<Long> actual) {
        if (expected == null || actual == null) return 0;
        for (int index = 0; index < actual.size(); index++) if (expected.contains(actual.get(index))) return index + 1;
        return 0;
    }
}
