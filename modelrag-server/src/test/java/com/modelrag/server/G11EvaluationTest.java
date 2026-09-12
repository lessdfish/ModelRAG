package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.observability.JdbcRetrievalTraceSink;
import com.modelrag.common.observability.RetrievalTraceContext;
import com.modelrag.common.observability.RetrievalTraceSink;
import com.modelrag.server.eval.EvalCategory;
import com.modelrag.server.eval.EvalEvidenceGroup;
import com.modelrag.server.eval.EvalLabels;
import com.modelrag.server.eval.EvaluationMetrics;
import com.modelrag.server.eval.EvaluationObservation;
import com.modelrag.server.eval.ObservedEvidenceIdentity;
import com.modelrag.server.eval.V2CutoverReadinessReport;
import com.modelrag.server.benchmark.RetrievalBenchmarkController;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.search.channel.v2.LexicalSearchPort;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import com.modelrag.search.dto.RetrievalV2Stages;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class G11EvaluationTest {
    @Test
    void canonicalMetricsCompareDocumentNodeAndCompleteEvidenceIdentities() {
        EvalLabels labels = new EvalLabels(List.of(1L), List.of(10L), List.of(101L),
                List.of(new EvalEvidenceGroup("fact-a", List.of(10L), List.of(101L), List.of(1001L))),
                EvalLabels.COMPARABLE, EvalLabels.COMPARABLE, EvalLabels.COMPARABLE);
        EvaluationObservation observation = new EvaluationObservation("V2", "trace", List.of(), List.of(),
                List.of(11L, 10L), List.of(10L), List.of(101L), List.of(101L), List.of(1001L),
                List.of(1001L), List.of(1001L), List.of(1002L), List.of(1001L), "answer", false, false,
                false, 12, 4, 2, 20L, 8L, Map.of("semantic", 3L),
                List.of(new ObservedEvidenceIdentity(10, 29L, 101L, 1001L, 1, true)));

        EvaluationMetrics metrics = new EvaluationMetrics();
        EvaluationMetrics.CaseScore score = metrics.score(labels, observation, "answer", false);
        EvaluationMetrics.Aggregate aggregate = metrics.aggregate(List.of(labels), List.of(observation), List.of(score));

        assertEquals(1.0, aggregate.values().get("documentRecallAt5"));
        assertEquals(1.0, aggregate.values().get("documentRecallAt20"));
        assertEquals(.5, aggregate.values().get("documentMrr"));
        assertEquals(1.0, aggregate.values().get("nodeRecall"));
        assertEquals(1.0, aggregate.values().get("completeEvidenceRecall"));
        assertEquals(EvalLabels.COMPARABLE, aggregate.status().get("nodeRecall"));
        assertEquals(4L, aggregate.telemetry().get("actionCount"));
        assertEquals(20L, aggregate.telemetry().get("inputTokens"));
        assertEquals(12.0, aggregate.values().get("p50LatencyMs"));
    }

    @Test
    void completeEvidenceDoesNotCrossMatchIndependentIdentityDimensions() {
        EvalLabels labels = new EvalLabels(List.of(), List.of(10L), List.of(200L),
                List.of(new EvalEvidenceGroup("crossed", List.of(10L), List.of(200L), List.of(1000L))),
                EvalLabels.COMPARABLE, EvalLabels.COMPARABLE, EvalLabels.COMPARABLE);
        EvaluationObservation observation = new EvaluationObservation("V2", "trace", List.of(), List.of(),
                List.of(10L, 20L), List.of(), List.of(100L, 200L), List.of(), List.of(1000L, 2000L),
                List.of(), List.of(), List.of(), List.of(), "", false, false, false, 1, 1, 0, null, null,
                Map.of(), List.of(new ObservedEvidenceIdentity(10, 1L, 100L, 1000L, 1, true),
                        new ObservedEvidenceIdentity(20, 1L, 200L, 2000L, 2, true)));
        EvaluationMetrics metrics = new EvaluationMetrics();
        EvaluationMetrics.CaseScore score = metrics.score(labels, observation, "", false);

        assertEquals(0.0, score.completeEvidenceRecall());
    }

    @Test
    void unlabeledCasesAreExcludedFromMetricDenominators() {
        EvalLabels labeled = new EvalLabels(List.of(), List.of(10L), List.of(), List.of(),
                EvalLabels.COMPARABLE, EvalLabels.INSUFFICIENT_LABELS, EvalLabels.INSUFFICIENT_LABELS);
        EvalLabels unlabeled = new EvalLabels(List.of(), List.of(), List.of(), List.of(),
                EvalLabels.INSUFFICIENT_LABELS, EvalLabels.INSUFFICIENT_LABELS, EvalLabels.INSUFFICIENT_LABELS);
        EvaluationObservation hit = new EvaluationObservation("V2", "a", List.of(), List.of(), List.of(10L),
                List.of(), List.of(), List.of(), List.of(), List.of(), "expected", false, false, false,
                1, 0, 0, null, null, Map.of());
        EvaluationObservation missingLabels = new EvaluationObservation("V2", "b", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), "", false, false, false,
                1, 0, 0, null, null, Map.of());
        EvaluationMetrics metrics = new EvaluationMetrics();
        List<EvaluationMetrics.CaseScore> scores = List.of(metrics.score(labeled, hit, "expected", false),
                metrics.score(unlabeled, missingLabels, "", false));
        EvaluationMetrics.Aggregate aggregate = metrics.aggregate(List.of(labeled, unlabeled),
                List.of(hit, missingLabels), scores);

        assertEquals(1.0, aggregate.values().get("documentRecallAt20"));
        assertEquals(1.0, aggregate.values().get("answerAccuracy"));
        assertEquals(1L, aggregate.labelCoverage().get("documentLabels"));
        assertEquals(1L, aggregate.labelCoverage().get("answerLabels"));
    }

    @Test
    void legacyChunkLabelsAreExplicitlyMarkedAndNeverBecomeV2NodeLabels() {
        EvalLabels labels = new EvalLabels(List.of(1L), List.of(10L), List.of(), List.of(),
                EvalLabels.LEGACY_LABEL_ONLY, EvalLabels.INSUFFICIENT_LABELS, EvalLabels.INSUFFICIENT_LABELS);
        EvaluationObservation observation = new EvaluationObservation("V1", "trace", List.of(1L), List.of(1L),
                List.of(10L), List.of(10L), List.of(), List.of(), List.of(), List.of(), "", false, false,
                false, 1, 1, 0, null, null, Map.of());
        EvaluationMetrics metrics = new EvaluationMetrics();
        EvaluationMetrics.Aggregate aggregate = metrics.aggregate(List.of(labels), List.of(observation),
                List.of(metrics.score(labels, observation, "", false)));

        assertEquals(EvalLabels.LEGACY_LABEL_ONLY, aggregate.status().get("documentRecallAt20"));
        assertEquals(EvalLabels.INSUFFICIENT_LABELS, aggregate.status().get("nodeRecall"));
        assertTrue(labels.expectedNodeIds().isEmpty());
        assertTrue(labels.expectedEvidenceGroups().isEmpty());
    }

    @Test
    void categoriesAndReadinessDecisionAreBoundedAndExplicit() throws Exception {
        assertEquals(List.of("POINT_FACT", "CROSS_SECTION", "CROSS_DOCUMENT", "MULTI_HOP",
                "WHOLE_DOCUMENT", "GLOBAL", "TABLE", "VERSION_SENSITIVE", "REFERENCE", "REFUSAL"),
                EvalCategory.canonicalNames());
        V2CutoverReadinessReport report = V2CutoverReadinessReport.notReady("full benchmark not run");
        assertEquals(V2CutoverReadinessReport.NOT_READY_FOR_G12, report.decision());
        assertFalse(report.fullScaleBenchmarkRun());
        assertTrue(report.failReasons().get(0).contains("full benchmark"));

        String v65 = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V65__retrieval_observability.sql"));
        String v66 = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V66__evaluation_v2_labels.sql"));
        assertTrue(v65.contains("kb_retrieval_action"));
        assertTrue(v65.contains("kb_retrieval_evidence"));
        assertTrue(v66.contains("expected_document_ids"));
        assertTrue(v66.contains("expected_node_ids"));
        assertTrue(v66.contains("expected_evidence_groups"));
        assertFalse(v65.contains("outOfOrder"));
        assertFalse(v66.contains("outOfOrder"));
        Path migrations = Path.of("src", "main", "resources", "db", "migration");
        assertTrue(Files.exists(migrations.resolve("V64__agent_checkpoint.sql")));
        assertFalse(Files.exists(migrations.resolve("V61__retrieval_trace_header_and_actions.sql")));
        assertFalse(Files.exists(migrations.resolve("V62__retrieval_evidence.sql")));
        assertFalse(Files.exists(migrations.resolve("V63__resource_acl.sql")));
    }

    @Test
    void retrievalBenchmarkEndpointIsRestrictedToBenchmarkProfile() {
        try (AnnotationConfigApplicationContext production = endpointContext("production")) {
            assertTrue(production.getBeansOfType(RetrievalBenchmarkController.class).isEmpty());
        }
        try (AnnotationConfigApplicationContext benchmark = endpointContext("benchmark")) {
            assertEquals(1, benchmark.getBeansOfType(RetrievalBenchmarkController.class).size());
        }
    }

    @Test
    void benchmarkCorrectnessComesFromActiveRepositoryAndSentinelRecall() {
        String sentinel = "G11_ACTIVE_BUILD_SENTINEL_10001";
        RetrievalCandidate candidate = new RetrievalCandidate(7, 101, 19, 23, 29, 10_001,
                RetrievalUnitType.PARAGRAPH, "Benchmark", sentinel, 1, RetrievalChannel.LEXICAL, 1, Map.of());
        RetrievalUnit active = new RetrievalUnit(101, 7, 23, 29, 19, 10_001,
                RetrievalUnitType.PARAGRAPH, 0, "Benchmark", sentinel, "hash", 1,
                Map.of("fixtureIdentity", "fixture"), Instant.now());
        HybridRetrievalService retrieval = mock(HybridRetrievalService.class);
        when(retrieval.inspect(any(), any())).thenReturn(new RetrievalV2Stages("", List.of(), "", List.of(),
                List.of(candidate), List.of(candidate), List.of(), List.of(candidate), false, List.of(), Map.of()));
        RetrievalUnitRepository units = mock(RetrievalUnitRepository.class);
        when(units.findActiveByIds(any(Long.class), any())).thenReturn(List.of(active));
        RetrievalBenchmarkController controller = new RetrievalBenchmarkController(retrieval,
                mock(IndexBuildRepository.class), units, mock(LexicalSearchPort.class));

        var response = controller.retrieve(new RetrievalBenchmarkController.Request(7, sentinel,
                "high-active-build-count", "lexical", null, null, true, "fixture")).getBody();

        assertEquals(0, response.staleCandidateCount());
        assertFalse(response.activeBuildTruncated());
        assertTrue(response.evidenceValid());
        assertEquals(System.getProperty("java.version"), response.serverJavaVersion());

        when(units.findActiveByIds(any(Long.class), any())).thenReturn(List.of());
        response = controller.retrieve(new RetrievalBenchmarkController.Request(7, sentinel,
                "high-active-build-count", "lexical", null, null, true, "fixture")).getBody();
        assertEquals(1, response.staleCandidateCount());
        assertTrue(response.activeBuildTruncated());
        assertFalse(response.evidenceValid());
    }

    @Test
    void technicalTraceFailureIsErrorAndNeverBusinessRefusal() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicReference<String> sql = new AtomicReference<>();
        doAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            return 1;
        }).when(jdbc).update(anyString(), any(Object[].class));
        JdbcRetrievalTraceSink sink = new JdbcRetrievalTraceSink(jdbc, new ObjectMapper());

        sink.fail(new RetrievalTraceContext("request", "trace", null, "V2", 7, null, "qwen3-v1"),
                "technical-failure", new RetrievalTraceSink.Completion(1, 0, 10, true, List.of()));

        assertTrue(sql.get().contains("status='ERROR'"));
        assertTrue(sql.get().contains("refused=FALSE"));
    }

    private AnnotationConfigApplicationContext endpointContext(String profile) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.registerBean(HybridRetrievalService.class, () -> mock(HybridRetrievalService.class));
        context.registerBean(IndexBuildRepository.class, () -> mock(IndexBuildRepository.class));
        context.registerBean(RetrievalUnitRepository.class, () -> mock(RetrievalUnitRepository.class));
        context.registerBean(LexicalSearchPort.class, () -> mock(LexicalSearchPort.class));
        context.register(RetrievalBenchmarkController.class);
        context.refresh();
        return context;
    }
}
