package com.modelrag.server.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Deterministic, adapter-independent retrieval and answer metrics. */
@Component
public class EvaluationMetrics {
    public record CaseScore(double documentRecallAt5, double documentRecallAt20, double documentMrr,
            double documentNdcg, Double nodeRecall, Double completeEvidenceRecall, double evidencePrecision,
            boolean answerChecked, boolean answerCorrect, double answerRelevance, double faithfulness,
            boolean refusalCorrect, boolean shouldRefuse, int documentRank, int nodeRank) { }

    public record Aggregate(Map<String, Double> values, Map<String, String> status,
            Map<String, Long> telemetry, Map<String, Double> latencyMs, Map<String, Object> labelCoverage) {
        public Aggregate {
            values = values == null ? Map.of() : Map.copyOf(values);
            status = status == null ? Map.of() : Map.copyOf(status);
            telemetry = telemetry == null ? Map.of() : Map.copyOf(telemetry);
            latencyMs = latencyMs == null ? Map.of() : Map.copyOf(latencyMs);
            labelCoverage = labelCoverage == null ? Map.of() : Map.copyOf(labelCoverage);
        }
    }

    public CaseScore score(EvalLabels labels, EvaluationObservation observation,
            String expectedAnswer, boolean shouldRefuse) {
        return score(labels, observation, null, expectedAnswer, shouldRefuse);
    }

    public CaseScore score(EvalLabels labels, EvaluationObservation observation, String question,
            String expectedAnswer, boolean shouldRefuse) {
        List<Long> expectedDocuments = labels.expectedDocumentIds();
        List<Long> actualDocuments = observation.finalDocumentIds();
        int documentRank = rank(expectedDocuments, actualDocuments);
        int nodeRank = rank(labels.expectedNodeIds(), observation.finalNodeIds());
        double documentRecallAt5 = recall(expectedDocuments, take(actualDocuments, 5));
        double documentRecallAt20 = recall(expectedDocuments, take(actualDocuments, 20));
        double documentMrr = documentRank == 0 ? 0 : 1.0 / documentRank;
        double documentNdcg = ndcg(expectedDocuments, actualDocuments);
        Double nodeRecall = labels.expectedNodeIds().isEmpty() ? null
                : recall(labels.expectedNodeIds(), observation.finalNodeIds());
        Double completeEvidenceRecall = labels.expectedEvidenceGroups().isEmpty() ? null
                : completeEvidenceRecall(labels.expectedEvidenceGroups(), observation);
        double evidencePrecision = expectedDocuments.isEmpty() ? 0
                : precision(expectedDocuments, actualDocuments);
        boolean answerChecked = expectedAnswer != null && !expectedAnswer.isBlank();
        boolean answerCorrect = answerChecked && contains(observation.answer(), expectedAnswer);
        double relevance = answerRelevance(observation.answer(), question == null ? expectedAnswer : question);
        // Faithfulness requires a judge or a bounded evidence-text comparison. This deterministic runner
        // deliberately leaves it non-comparable instead of treating a non-empty answer as proof.
        double faithfulness = 0;
        return new CaseScore(documentRecallAt5, documentRecallAt20, documentMrr, documentNdcg, nodeRecall,
                completeEvidenceRecall, evidencePrecision, answerChecked, answerCorrect, relevance, faithfulness,
                observation.refused() == shouldRefuse, shouldRefuse, documentRank, nodeRank);
    }

    public Aggregate aggregate(List<EvalLabels> labels, List<EvaluationObservation> observations,
            List<CaseScore> cases) {
        int total = cases == null ? 0 : cases.size();
        if (total == 0) return new Aggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, String> status = new LinkedHashMap<>();
        List<Integer> documentCases = eligible(labels, value -> !value.expectedDocumentIds().isEmpty());
        boolean documentLabels = !documentCases.isEmpty();
        averageIfAvailable(values, "documentRecallAt5", average(cases, documentCases, CaseScore::documentRecallAt5),
                documentLabels, documentLabels ? statusFor(labels, "document") : EvalLabels.INSUFFICIENT_LABELS, status);
        averageIfAvailable(values, "documentRecallAt20", average(cases, documentCases, CaseScore::documentRecallAt20),
                documentLabels, documentLabels ? statusFor(labels, "document") : EvalLabels.INSUFFICIENT_LABELS, status);
        averageIfAvailable(values, "documentMrr", average(cases, documentCases, CaseScore::documentMrr),
                documentLabels, documentLabels ? statusFor(labels, "document") : EvalLabels.INSUFFICIENT_LABELS, status);
        averageIfAvailable(values, "documentNdcg", average(cases, documentCases, CaseScore::documentNdcg),
                documentLabels, documentLabels ? statusFor(labels, "document") : EvalLabels.INSUFFICIENT_LABELS, status);
        boolean v2Identities = observations.stream().allMatch(value -> "V2".equalsIgnoreCase(value.variant()));
        averageNullable(values, "nodeRecall", cases.stream().map(CaseScore::nodeRecall).filter(value -> value != null)
                .mapToDouble(Double::doubleValue).average().orElse(0),
                labels.stream().anyMatch(value -> !value.expectedNodeIds().isEmpty()),
                v2Identities ? EvalLabels.COMPARABLE : EvalLabels.V2_ONLY, status);
        averageNullable(values, "completeEvidenceRecall", cases.stream().map(CaseScore::completeEvidenceRecall)
                .filter(value -> value != null).mapToDouble(Double::doubleValue).average().orElse(0),
                labels.stream().anyMatch(value -> !value.expectedEvidenceGroups().isEmpty()),
                v2Identities ? EvalLabels.COMPARABLE : EvalLabels.V2_ONLY, status);
        averageIfAvailable(values, "evidencePrecision", average(cases, documentCases, CaseScore::evidencePrecision),
                documentLabels, documentLabels ? statusFor(labels, "document") : EvalLabels.INSUFFICIENT_LABELS, status);

        long answerChecked = cases.stream().filter(CaseScore::answerChecked).count();
        long answerCorrect = cases.stream().filter(CaseScore::answerCorrect).count();
        averageIfAvailable(values, "answerAccuracy", answerChecked == 0 ? 0 : (double) answerCorrect / answerChecked,
                answerChecked > 0, answerChecked == 0 ? EvalLabels.INSUFFICIENT_LABELS : EvalLabels.COMPARABLE, status);
        averageIfAvailable(values, "answerRelevance", cases.stream().filter(CaseScore::answerChecked)
                        .mapToDouble(CaseScore::answerRelevance).average().orElse(0),
                answerChecked > 0, answerChecked == 0 ? EvalLabels.INSUFFICIENT_LABELS : EvalLabels.COMPARABLE, status);
        status.put("faithfulness", answerChecked == 0 ? EvalLabels.INSUFFICIENT_LABELS : EvalLabels.NON_COMPARABLE);

        long predictedRefusals = observations.stream().filter(EvaluationObservation::refused).count();
        long actualExpectedRefusal = 0;
        long refusalTruePositive = 0;
        for (int index = 0; index < observations.size(); index++) {
            boolean expected = cases.get(index).shouldRefuse();
            if (expected) actualExpectedRefusal++;
            if (expected && observations.get(index).refused()) refusalTruePositive++;
        }
        averageIfAvailable(values, "refusalPrecision", predictedRefusals == 0 ? 0 : (double) refusalTruePositive / predictedRefusals,
                actualExpectedRefusal > 0, actualExpectedRefusal == 0 ? EvalLabels.INSUFFICIENT_LABELS : EvalLabels.COMPARABLE, status);
        averageIfAvailable(values, "refusalRecall", actualExpectedRefusal == 0 ? 0 : (double) refusalTruePositive / actualExpectedRefusal,
                actualExpectedRefusal > 0, actualExpectedRefusal == 0 ? EvalLabels.INSUFFICIENT_LABELS : EvalLabels.COMPARABLE, status);
        averageIfAvailable(values, "refusalAccuracy", (double) cases.stream().filter(CaseScore::refusalCorrect).count() / total,
                total > 0, EvalLabels.COMPARABLE, status);

        List<Long> latencies = observations.stream().map(EvaluationObservation::latencyMs).sorted().toList();
        values.put("p50LatencyMs", percentile(latencies, .50));
        values.put("p95LatencyMs", percentile(latencies, .95));
        values.put("p99LatencyMs", percentile(latencies, .99));
        status.put("p50LatencyMs", EvalLabels.COMPARABLE);
        status.put("p95LatencyMs", EvalLabels.COMPARABLE);
        status.put("p99LatencyMs", EvalLabels.COMPARABLE);

        long errors = observations.stream().filter(EvaluationObservation::error).count();
        long degraded = observations.stream().filter(EvaluationObservation::degraded).count();
        values.put("errorRate", (double) errors / total);
        values.put("degradedRate", (double) degraded / total);
        status.put("errorRate", EvalLabels.COMPARABLE);
        status.put("degradedRate", EvalLabels.COMPARABLE);
        Map<String, Long> telemetry = new LinkedHashMap<>();
        telemetry.put("errors", errors);
        telemetry.put("degraded", degraded);
        telemetry.put("actionCount", observations.stream().mapToLong(EvaluationObservation::actionCount).sum());
        telemetry.put("navigationCount", observations.stream().mapToLong(EvaluationObservation::navigationCount).sum());
        telemetry.put("predictedRefusals", predictedRefusals);
        telemetry.put("expectedRefusals", actualExpectedRefusal);
        long inputTokens = observations.stream().filter(value -> value.inputTokens() != null)
                .mapToLong(value -> value.inputTokens()).sum();
        long outputTokens = observations.stream().filter(value -> value.outputTokens() != null)
                .mapToLong(value -> value.outputTokens()).sum();
        telemetry.put("inputTokens", inputTokens);
        telemetry.put("outputTokens", outputTokens);
        status.put("inputTokens", observations.stream().anyMatch(value -> value.inputTokens() != null)
                ? EvalLabels.COMPARABLE : "MISSING");
        status.put("outputTokens", observations.stream().anyMatch(value -> value.outputTokens() != null)
                ? EvalLabels.COMPARABLE : "MISSING");

        Map<String, Double> latencyMs = new LinkedHashMap<>();
        observations.stream().flatMap(value -> value.stageLatencyMs().entrySet().stream())
                .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                        Collectors.averagingLong(Map.Entry::getValue)))
                .forEach(latencyMs::put);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("documentLabels", count(labels, "document"));
        coverage.put("nodeLabels", count(labels, "node"));
        coverage.put("evidenceGroups", count(labels, "evidence"));
        coverage.put("answerLabels", answerChecked);
        coverage.put("totalCaseCount", total);
        coverage.put("eligibleCaseCount", Map.of("document", documentCases.size(),
                "node", count(labels, "node"), "completeEvidence", count(labels, "evidence"),
                "answer", answerChecked));
        coverage.put("coverage", Map.of("document", ratio(documentCases.size(), total),
                "node", ratio(count(labels, "node"), total),
                "completeEvidence", ratio(count(labels, "evidence"), total),
                "answer", ratio(answerChecked, total)));
        coverage.put("legacyLabelOnly", labels.stream().filter(value -> EvalLabels.LEGACY_LABEL_ONLY.equals(value.overallStatus())).count());
        return new Aggregate(values, status, telemetry, latencyMs, coverage);
    }

    private String statusFor(List<EvalLabels> labels, String type) {
        boolean any = labels.stream().anyMatch(value -> switch (type) {
            case "document" -> !value.expectedDocumentIds().isEmpty();
            case "node" -> !value.expectedNodeIds().isEmpty();
            default -> !value.expectedEvidenceGroups().isEmpty();
        });
        if (!any) return EvalLabels.INSUFFICIENT_LABELS;
        return labels.stream().anyMatch(value -> EvalLabels.LEGACY_LABEL_ONLY.equals(value.overallStatus()))
                ? EvalLabels.LEGACY_LABEL_ONLY : EvalLabels.COMPARABLE;
    }

    private long count(List<EvalLabels> labels, String type) {
        return labels.stream().filter(value -> switch (type) {
            case "document" -> !value.expectedDocumentIds().isEmpty();
            case "node" -> !value.expectedNodeIds().isEmpty();
            default -> !value.expectedEvidenceGroups().isEmpty();
        }).count();
    }

    private void average(Map<String, Double> values, String name, double value, String metricStatus,
            Map<String, String> status) {
        values.put(name, value);
        status.put(name, metricStatus);
    }

    private void averageIfAvailable(Map<String, Double> values, String name, double value, boolean available,
            String metricStatus, Map<String, String> status) {
        if (available) values.put(name, value);
        status.put(name, available ? metricStatus : EvalLabels.INSUFFICIENT_LABELS);
    }

    private void averageNullable(Map<String, Double> values, String name, double value, boolean available,
            String availableStatus, Map<String, String> status) {
        if (available) values.put(name, value);
        status.put(name, !available ? EvalLabels.INSUFFICIENT_LABELS : availableStatus);
    }

    private double completeEvidenceRecall(List<EvalEvidenceGroup> groups, EvaluationObservation observation) {
        if (groups.isEmpty()) return 0;
        long complete = groups.stream().filter(group -> groupSatisfied(group, observation)).count();
        return (double) complete / groups.size();
    }

    private boolean groupSatisfied(EvalEvidenceGroup group, EvaluationObservation observation) {
        return observation.evidenceIdentities().stream().filter(ObservedEvidenceIdentity::selected).anyMatch(identity ->
                (group.documentIds().isEmpty() || group.documentIds().contains(identity.documentId()))
                && (group.nodeIds().isEmpty() || group.nodeIds().contains(identity.nodeId()))
                && (group.retrievalUnitIds().isEmpty()
                        || group.retrievalUnitIds().contains(identity.retrievalUnitId())));
    }

    private List<Integer> eligible(List<EvalLabels> labels, java.util.function.Predicate<EvalLabels> predicate) {
        List<Integer> result = new java.util.ArrayList<>();
        for (int index = 0; index < labels.size(); index++) if (predicate.test(labels.get(index))) result.add(index);
        return List.copyOf(result);
    }

    private double average(List<CaseScore> cases, List<Integer> indexes,
            java.util.function.ToDoubleFunction<CaseScore> value) {
        return indexes.stream().mapToDouble(index -> value.applyAsDouble(cases.get(index))).average().orElse(0);
    }

    private double ratio(long eligible, long total) { return total == 0 ? 0 : (double) eligible / total; }

    private double precision(List<Long> expected, List<Long> actual) {
        return actual.isEmpty() ? 0 : (double) hits(expected, actual) / actual.size();
    }

    private double recall(List<Long> expected, List<Long> actual) {
        return expected.isEmpty() ? 0 : (double) hits(expected, actual) / expected.size();
    }

    private int hits(List<Long> expected, List<Long> actual) {
        Set<Long> values = Set.copyOf(expected);
        return (int) actual.stream().filter(values::contains).count();
    }

    private int rank(List<Long> expected, List<Long> actual) {
        Set<Long> values = Set.copyOf(expected);
        for (int index = 0; index < actual.size(); index++) if (values.contains(actual.get(index))) return index + 1;
        return 0;
    }

    private double ndcg(List<Long> expected, List<Long> actual) {
        if (expected.isEmpty() || actual.isEmpty()) return 0;
        double dcg = 0;
        Set<Long> values = Set.copyOf(expected);
        for (int index = 0; index < actual.size(); index++) if (values.contains(actual.get(index))) dcg += 1.0 / log2(index + 2);
        double ideal = 0;
        for (int index = 0; index < Math.min(expected.size(), actual.size()); index++) ideal += 1.0 / log2(index + 2);
        return ideal == 0 ? 0 : dcg / ideal;
    }

    private double answerRelevance(String answer, String expected) {
        if (expected == null || expected.isBlank()) return 0;
        return contains(answer, expected) ? 1 : overlap(answer, expected);
    }

    private boolean contains(String value, String expected) {
        String answer = normalize(value);
        String target = normalize(expected);
        if (target.isBlank()) return true;
        if (answer.contains(target)) return true;
        return target.length() >= 4 && overlap(answer, target) >= .75;
    }

    private double overlap(String left, String right) {
        String l = normalize(left);
        String r = normalize(right);
        if (l.isBlank() || r.isBlank() || l.length() < 2) return 0;
        int matched = 0;
        int total = 0;
        for (int index = 0; index + 1 < l.length(); index++) {
            total++;
            if (r.contains(l.substring(index, index + 2))) matched++;
        }
        return total == 0 ? 0 : (double) matched / total;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s，。！？、：:；;]", "").toLowerCase();
    }

    private List<Long> take(List<Long> values, int count) {
        return values.stream().limit(count).toList();
    }

    private double percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return 0;
        return values.get(Math.min(values.size() - 1, (int) Math.round((values.size() - 1) * fraction)));
    }

    private double log2(double value) { return Math.log(value) / Math.log(2); }
}
