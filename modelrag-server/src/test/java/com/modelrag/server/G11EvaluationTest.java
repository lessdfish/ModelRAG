package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.server.eval.EvalCategory;
import com.modelrag.server.eval.EvalEvidenceGroup;
import com.modelrag.server.eval.EvalLabels;
import com.modelrag.server.eval.EvaluationMetrics;
import com.modelrag.server.eval.EvaluationObservation;
import com.modelrag.server.eval.V2CutoverReadinessReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class G11EvaluationTest {
    @Test
    void canonicalMetricsCompareDocumentNodeAndCompleteEvidenceIdentities() {
        EvalLabels labels = new EvalLabels(List.of(1L), List.of(10L), List.of(101L),
                List.of(new EvalEvidenceGroup("fact-a", List.of(10L), List.of(101L), List.of(1001L))),
                EvalLabels.COMPARABLE, EvalLabels.COMPARABLE, EvalLabels.COMPARABLE);
        EvaluationObservation observation = new EvaluationObservation("V2", "trace", List.of(), List.of(),
                List.of(11L, 10L), List.of(10L), List.of(101L), List.of(101L), List.of(1001L),
                List.of(1001L), List.of(1001L), List.of(1002L), List.of(1001L), "answer", false, false,
                false, 12, 4, 2, 20L, 8L, Map.of("semantic", 3L));

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
}
