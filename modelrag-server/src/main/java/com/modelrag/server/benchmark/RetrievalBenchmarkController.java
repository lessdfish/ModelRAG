package com.modelrag.server.benchmark;

import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.search.channel.v2.DocumentLexicalSearchRequest;
import com.modelrag.search.channel.v2.LexicalSearchPort;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.orchestrator.HybridRetrievalService;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Real V2 retrieval endpoint that is absent unless the benchmark profile is active. */
@RestController
@Profile("benchmark")
@RequestMapping("/internal/benchmarks")
public class RetrievalBenchmarkController {
    private static final List<String> WORKLOADS = List.of("semantic-only", "lexical-only", "hybrid",
            "document-scoped", "broad", "stale-build-exclusion", "high-active-build-count");
    private final HybridRetrievalService retrieval;
    private final IndexBuildRepository builds;
    private final RetrievalUnitRepository units;
    private final LexicalSearchPort lexical;
    private final MeterRegistry metrics;

    public RetrievalBenchmarkController(HybridRetrievalService retrieval, IndexBuildRepository builds,
            RetrievalUnitRepository units, LexicalSearchPort lexical) {
        this(retrieval, builds, units, lexical, new SimpleMeterRegistry());
    }

    @Autowired
    public RetrievalBenchmarkController(HybridRetrievalService retrieval, IndexBuildRepository builds,
            RetrievalUnitRepository units, LexicalSearchPort lexical, ObjectProvider<MeterRegistry> metrics) {
        this(retrieval, builds, units, lexical, metrics.getIfAvailable(SimpleMeterRegistry::new));
    }

    private RetrievalBenchmarkController(HybridRetrievalService retrieval, IndexBuildRepository builds,
            RetrievalUnitRepository units, LexicalSearchPort lexical, MeterRegistry metrics) {
        this.retrieval = retrieval;
        this.builds = builds;
        this.units = units;
        this.lexical = lexical;
        this.metrics = metrics;
    }

    @PostMapping("/retrieval")
    public ResponseEntity<Response> retrieve(@RequestBody Request request) {
        if (request == null || request.datasetId() <= 0 || request.query() == null || request.query().isBlank()
                || !WORKLOADS.contains(request.workload())) return ResponseEntity.badRequest().build();
        long started = System.nanoTime();
        if ("document-scoped".equals(request.workload())) return documentScoped(request, started);
        HybridRetrievalService.Mode mode = switch (request.workload()) {
            case "semantic-only" -> HybridRetrievalService.Mode.SEMANTIC_ONLY;
            case "lexical-only", "high-active-build-count" -> HybridRetrievalService.Mode.LEXICAL_ONLY;
            default -> HybridRetrievalService.Mode.HYBRID;
        };
        RetrievalV2Stages stages = retrieval.inspect(new RetrievalV2Request(request.datasetId(), request.query(),
                20, 0, "qwen3-v1"), mode);
        List<RetrievalCandidate> candidates = stages.finalCandidates();
        if (request.documentId() != null) {
            candidates = candidates.stream().filter(value -> value.documentId() == request.documentId()).toList();
        }
        ValidatedCandidates validation = validateActive(request, candidates, null);
        boolean sentinelRecall = "high-active-build-count".equals(request.workload())
                && validation.candidates().stream().anyMatch(value -> value.content().contains(request.query()));
        boolean truncated = request.activeBuildOverflow() && "high-active-build-count".equals(request.workload())
                && !sentinelRecall;
        Map<String, Long> latency = new java.util.LinkedHashMap<>(stages.latencyMs());
        latency.put("endpointTotal", (System.nanoTime() - started) / 1_000_000L);
        List<String> timeoutComponents = timeoutComponents(stages);
        return ResponseEntity.ok(new Response(!stages.degradedComponents().isEmpty(), !timeoutComponents.isEmpty(), latency,
                stages.performanceLatencyMs(), stages.performanceAttributes(), timeoutComponents,
                stages.degradedComponents(),
                timeoutComponents.isEmpty() ? "NONE" : "TIMED_OUT_AT_CHANNEL_BUDGET",
                validation.candidates().size(), validation.staleCandidateCount(), truncated,
                sentinelRecall, validation.candidates().size() <= RetrievalV2Request.MAX_TOP_K,
                validation.status() != EvidenceValidationStatus.INVALID, validation.status(),
                System.getProperty("java.version")));
    }

    private ResponseEntity<Response> documentScoped(Request request, long started) {
        if (request.documentId() == null || request.documentId() <= 0) return ResponseEntity.badRequest().build();
        IndexBuild build = builds.findActiveByDocumentId(request.documentId()).orElse(null);
        if (build == null || build.state() != IndexBuildState.ACTIVE || build.datasetId() != request.datasetId()) {
            return ResponseEntity.ok(new Response(true, false,
                    Map.of("endpointTotal", (System.nanoTime() - started) / 1_000_000L),
                    Map.of(), Map.of(), List.of(), List.of(), "NONE",
                    0, 0, false, false, true, true, EvidenceValidationStatus.NO_EVIDENCE,
                    System.getProperty("java.version")));
        }
        List<RetrievalCandidate> candidates = lexical.findInDocument(new DocumentLexicalSearchRequest(
                request.datasetId(), request.documentId(), request.query(), List.of(build.id()), 20));
        ValidatedCandidates validation = validateActive(request, candidates, build);
        return ResponseEntity.ok(new Response(false, false,
                Map.of("lexical", (System.nanoTime() - started) / 1_000_000L),
                Map.of(), Map.of(), List.of(), List.of(), "NONE", validation.candidates().size(),
                validation.staleCandidateCount(), false, false, validation.candidates().size() <= 20,
                validation.status() != EvidenceValidationStatus.INVALID, validation.status(),
                System.getProperty("java.version")));
    }

    private ValidatedCandidates validateActive(Request request, List<RetrievalCandidate> candidates,
            IndexBuild expectedBuild) {
        if (candidates.isEmpty()) return new ValidatedCandidates(List.of(), 0, EvidenceValidationStatus.NO_EVIDENCE);
        Map<Long, RetrievalUnit> activeById = new LinkedHashMap<>();
        units.findActiveByIds(request.datasetId(), candidates.stream()
                .map(RetrievalCandidate::retrievalUnitId).distinct().toList())
                .forEach(unit -> activeById.put(unit.id(), unit));
        List<RetrievalCandidate> valid = candidates.stream().filter(candidate -> {
            RetrievalUnit unit = activeById.get(candidate.retrievalUnitId());
            if (unit == null || unit.datasetId() != request.datasetId()
                    || unit.datasetId() != candidate.datasetId() || unit.documentId() != candidate.documentId()
                    || unit.documentVersionId() != candidate.documentVersionId() || unit.nodeId() != candidate.nodeId()
                    || unit.indexBuildId() != candidate.indexBuildId()) return false;
            if (expectedBuild != null && (unit.documentId() != expectedBuild.documentId()
                    || unit.documentVersionId() != expectedBuild.documentVersionId()
                    || unit.indexBuildId() != expectedBuild.id())) return false;
            String fixture = String.valueOf(unit.metadata().getOrDefault("fixtureIdentity", ""));
            return request.benchmarkIdentity() == null || request.benchmarkIdentity().isBlank()
                    || request.benchmarkIdentity().equals(fixture);
        }).toList();
        long stale = candidates.size() - valid.size();
        return new ValidatedCandidates(valid, stale,
                stale == 0 ? EvidenceValidationStatus.VALID : EvidenceValidationStatus.INVALID);
    }

    private List<String> timeoutComponents(RetrievalV2Stages stages) {
        return stages.degradedComponents().stream().filter(component -> component.endsWith("_TIMEOUT")).toList();
    }

    @GetMapping("/retrieval/diagnostics")
    public PerformanceDiagnostics diagnostics() {
        Map<String, TimerStats> stages = metrics.find("modelrag.retrieval.v2.performance").timers().stream()
                .collect(Collectors.toMap(timer -> timer.getId().getTag("stage"), this::stats,
                        (left, right) -> right, LinkedHashMap::new));
        Map<String, Long> cache = metrics.find("modelrag.retrieval.v2.embedding.cache").counters().stream()
                .collect(Collectors.toMap(counter -> counter.getId().getTag("outcome"),
                        counter -> Math.round(counter.count()), Long::sum, LinkedHashMap::new));
        Map<String, Long> semantic = new LinkedHashMap<>();
        semantic.put("annRequests", counter("modelrag.retrieval.v2.semantic.ann.requests"));
        semantic.put("annRefilled", counter("modelrag.retrieval.v2.semantic.ann.refilled"));
        semantic.put("annRefillRounds", counter("modelrag.retrieval.v2.semantic.ann.refill-rounds"));
        semantic.put("annRefillExhausted", counter("modelrag.retrieval.v2.semantic.ann.refill-exhausted"));
        semantic.put("dbStatementTimeouts",
                counter("modelrag.retrieval.v2.semantic.db.statement-timeout"));
        return new PerformanceDiagnostics(stages, cache, semantic, System.getProperty("java.version"));
    }

    private long counter(String name) {
        Counter counter = metrics.find(name).counter();
        return counter == null ? 0 : Math.round(counter.count());
    }

    private TimerStats stats(Timer timer) {
        double p50 = 0;
        double p95 = 0;
        double p99 = 0;
        for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
            if (Math.abs(value.percentile() - .5) < .001) p50 = value.value(TimeUnit.MILLISECONDS);
            if (Math.abs(value.percentile() - .95) < .001) p95 = value.value(TimeUnit.MILLISECONDS);
            if (Math.abs(value.percentile() - .99) < .001) p99 = value.value(TimeUnit.MILLISECONDS);
        }
        return new TimerStats(timer.count(), p50, p95, p99,
                timer.mean(TimeUnit.MILLISECONDS), timer.max(TimeUnit.MILLISECONDS));
    }

    public record Request(long datasetId, String query, String workload, String mode, Long documentId,
            Long staleBuildId, boolean activeBuildOverflow, String benchmarkIdentity) { }

    public record Response(boolean degraded, boolean timeout, Map<String, Long> stageLatencyMs,
            Map<String, Double> performanceLatencyMs, Map<String, String> performanceAttributes,
            List<String> timeoutComponents, List<String> degradedComponents, String timeoutStatus,
            int candidateCount, long staleCandidateCount, boolean activeBuildTruncated,
            boolean sentinelRecall, boolean boundedResults, boolean evidenceValid,
            EvidenceValidationStatus evidenceStatus, String serverJavaVersion) { }

    private record ValidatedCandidates(List<RetrievalCandidate> candidates, long staleCandidateCount,
            EvidenceValidationStatus status) { }

    public record PerformanceDiagnostics(Map<String, TimerStats> stages, Map<String, Long> cacheOutcomes,
            Map<String, Long> semanticOutcomes, String serverJavaVersion) { }

    public record TimerStats(long count, double p50Ms, double p95Ms, double p99Ms,
            double meanMs, double maxMs) { }

    public enum EvidenceValidationStatus { NO_EVIDENCE, VALID, INVALID }
}
