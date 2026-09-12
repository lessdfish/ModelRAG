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
import org.springframework.context.annotation.Profile;
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

    public RetrievalBenchmarkController(HybridRetrievalService retrieval, IndexBuildRepository builds,
            RetrievalUnitRepository units, LexicalSearchPort lexical) {
        this.retrieval = retrieval;
        this.builds = builds;
        this.units = units;
        this.lexical = lexical;
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
        boolean truncated = "high-active-build-count".equals(request.workload())
                && validation.candidates().stream().noneMatch(value -> value.content().contains(request.query()));
        Map<String, Long> latency = new java.util.LinkedHashMap<>(stages.latencyMs());
        latency.put("endpointTotal", (System.nanoTime() - started) / 1_000_000L);
        return ResponseEntity.ok(new Response(!stages.degradedComponents().isEmpty(), false, latency,
                validation.candidates().size(), validation.staleCandidateCount(), truncated,
                validation.candidates().size() <= RetrievalV2Request.MAX_TOP_K, validation.evidenceValid(),
                System.getProperty("java.version")));
    }

    private ResponseEntity<Response> documentScoped(Request request, long started) {
        if (request.documentId() == null || request.documentId() <= 0) return ResponseEntity.badRequest().build();
        IndexBuild build = builds.findActiveByDocumentId(request.documentId()).orElse(null);
        if (build == null || build.state() != IndexBuildState.ACTIVE || build.datasetId() != request.datasetId()) {
            return ResponseEntity.ok(new Response(true, false,
                    Map.of("endpointTotal", (System.nanoTime() - started) / 1_000_000L),
                    0, 0, false, true, false, System.getProperty("java.version")));
        }
        List<RetrievalCandidate> candidates = lexical.findInDocument(new DocumentLexicalSearchRequest(
                request.datasetId(), request.documentId(), request.query(), List.of(build.id()), 20));
        ValidatedCandidates validation = validateActive(request, candidates, build);
        return ResponseEntity.ok(new Response(false, false,
                Map.of("lexical", (System.nanoTime() - started) / 1_000_000L), validation.candidates().size(),
                validation.staleCandidateCount(), false, validation.candidates().size() <= 20,
                validation.evidenceValid(), System.getProperty("java.version")));
    }

    private ValidatedCandidates validateActive(Request request, List<RetrievalCandidate> candidates,
            IndexBuild expectedBuild) {
        if (candidates.isEmpty()) return new ValidatedCandidates(List.of(), 0, false);
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
        return new ValidatedCandidates(valid, stale, stale == 0 && !valid.isEmpty());
    }

    public record Request(long datasetId, String query, String workload, String mode, Long documentId,
            Long staleBuildId, boolean activeBuildOverflow, String benchmarkIdentity) { }

    public record Response(boolean degraded, boolean timeout, Map<String, Long> stageLatencyMs,
            int candidateCount, long staleCandidateCount, boolean activeBuildTruncated,
            boolean boundedResults, boolean evidenceValid, String serverJavaVersion) { }

    private record ValidatedCandidates(List<RetrievalCandidate> candidates, long staleCandidateCount,
            boolean evidenceValid) { }
}
