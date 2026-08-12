package com.modelrag.search.dto;

import java.util.List;

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
        List<ScoredChunk> finalResults) { }
