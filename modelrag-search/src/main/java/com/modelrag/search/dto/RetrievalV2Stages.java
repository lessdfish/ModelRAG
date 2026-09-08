package com.modelrag.search.dto;

import java.util.List;
import java.util.Map;

/** Immutable V2 retrieval stages for shadow comparison and operational metrics. */
public record RetrievalV2Stages(String rewrittenQuery, List<String> searchQueries, String rerankQuery,
        List<RetrievalCandidate> semanticCandidates, List<RetrievalCandidate> lexicalCandidates,
        List<RetrievalCandidate> fusedCandidates, List<RetrievalCandidate> rerankedCandidates,
        List<RetrievalCandidate> finalCandidates, boolean rerankApplied,
        List<String> degradedComponents, Map<String, Long> latencyMs) {
    public RetrievalV2Stages {
        searchQueries = searchQueries == null ? List.of() : List.copyOf(searchQueries);
        semanticCandidates = semanticCandidates == null ? List.of() : List.copyOf(semanticCandidates);
        lexicalCandidates = lexicalCandidates == null ? List.of() : List.copyOf(lexicalCandidates);
        fusedCandidates = fusedCandidates == null ? List.of() : List.copyOf(fusedCandidates);
        rerankedCandidates = rerankedCandidates == null ? List.of() : List.copyOf(rerankedCandidates);
        finalCandidates = finalCandidates == null ? List.of() : List.copyOf(finalCandidates);
        degradedComponents = degradedComponents == null ? List.of() : List.copyOf(degradedComponents);
        latencyMs = latencyMs == null ? Map.of() : Map.copyOf(latencyMs);
    }

    public RetrievalV2Stages(String rewrittenQuery, List<String> searchQueries, String rerankQuery,
            List<RetrievalCandidate> semanticCandidates, List<RetrievalCandidate> lexicalCandidates,
            List<RetrievalCandidate> fusedCandidates, List<RetrievalCandidate> rerankedCandidates,
            boolean rerankApplied, List<RetrievalCandidate> finalCandidates) {
        this(rewrittenQuery, searchQueries, rerankQuery, semanticCandidates, lexicalCandidates,
                fusedCandidates, rerankedCandidates, finalCandidates, rerankApplied, List.of(), Map.of());
    }
}
