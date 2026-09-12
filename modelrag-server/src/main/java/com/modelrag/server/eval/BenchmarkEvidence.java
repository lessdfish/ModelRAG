package com.modelrag.server.eval;

import java.util.List;
import java.util.Map;

/** Evidence parsed from one completed real retrieval-scale benchmark report. */
public record BenchmarkEvidence(String status, String mode, String gitCommit, String fixtureIdentity,
        String sharedIndex, Map<String, Long> topology, List<String> workloads, List<Integer> concurrencies,
        double p50Ms, double p95Ms, double p99Ms,
        double errorRate, double degradedRate, double timeoutRate, boolean noStaleBuildLeakage,
        boolean noAclLeakage, boolean noActiveBuildTruncation, boolean boundedResults,
        boolean validEvidence, String reason) {
    public BenchmarkEvidence {
        status = status == null ? "NOT_RUN" : status;
        mode = mode == null ? "unknown" : mode;
        gitCommit = gitCommit == null ? "unknown" : gitCommit;
        fixtureIdentity = fixtureIdentity == null ? "unknown" : fixtureIdentity;
        sharedIndex = sharedIndex == null ? "unknown" : sharedIndex;
        topology = topology == null ? Map.of() : Map.copyOf(topology);
        workloads = workloads == null ? List.of() : List.copyOf(workloads);
        concurrencies = concurrencies == null ? List.of() : List.copyOf(concurrencies);
        reason = reason == null ? "" : reason;
    }

    public static BenchmarkEvidence notRun(String reason) {
        return new BenchmarkEvidence("NOT_RUN", "unknown", "unknown", "unknown", "unknown", Map.of(), List.of(), List.of(),
                0, 0, 0, 0, 0, 0, false, false, false, false, false, reason);
    }

    public boolean isVerifiedFull() {
        return "COMPLETED".equals(status) && "full".equals(mode)
                && "modelrag-retrieval-units-v2".equals(sharedIndex)
                && workloads.containsAll(List.of("semantic-only", "lexical-only", "hybrid", "document-scoped",
                        "broad", "stale-build-exclusion", "high-active-build-count"))
                && concurrencies.containsAll(List.of(1, 8, 32))
                && topology.getOrDefault("activeRetrievalUnits", 0L) >= 1_000_000
                && topology.getOrDefault("activeDocuments", 0L) >= 20_000
                && topology.getOrDefault("activeBuildCount", 0L)
                        > topology.getOrDefault("activeBuildFilterLimit", 10_000L)
                && topology.getOrDefault("staleRetrievalUnits", 0L) > 0
                && topology.getOrDefault("vectorDimension", 0L) == 1_024;
    }
}
