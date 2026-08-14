package com.modelrag.search.dto;

import java.util.List;
import java.util.Map;

/** Immutable retrieval stages retained for audit replay and offline evaluation. */
public record SearchStages(
        String rewrittenQuery,
        List<String> searchQueries,
        String rerankQuery,
        List<ScoredChunk> vectorResults,
        List<ScoredChunk> bm25Results,
        List<ScoredChunk> fusedResults,
        List<ScoredChunk> rerankResults,
        boolean rerankApplied,
        List<ScoredChunk> finalResults,
        List<String> degradedComponents,
        Map<String, Long> latencyMs) {

    public SearchStages(String rewrittenQuery, List<String> searchQueries, String rerankQuery,
            List<ScoredChunk> vectorResults, List<ScoredChunk> bm25Results,
            List<ScoredChunk> fusedResults, List<ScoredChunk> rerankResults,
            boolean rerankApplied, List<ScoredChunk> finalResults) {
        this(rewrittenQuery, searchQueries, rerankQuery, vectorResults, bm25Results,
                fusedResults, rerankResults, rerankApplied, finalResults, List.of(), Map.of());
    }

    public SearchStages {
        searchQueries = searchQueries == null ? List.of() : List.copyOf(searchQueries);
        vectorResults = vectorResults == null ? List.of() : List.copyOf(vectorResults);
        bm25Results = bm25Results == null ? List.of() : List.copyOf(bm25Results);
        fusedResults = fusedResults == null ? List.of() : List.copyOf(fusedResults);
        rerankResults = rerankResults == null ? List.of() : List.copyOf(rerankResults);
        finalResults = finalResults == null ? List.of() : List.copyOf(finalResults);
        degradedComponents = degradedComponents == null ? List.of() : List.copyOf(degradedComponents);
        latencyMs = latencyMs == null ? Map.of() : Map.copyOf(latencyMs);
    }
}
