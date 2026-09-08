package com.modelrag.search.shadow;

/** Compact bounded comparison result for V1/V2 retrieval shadow metrics. */
public record RetrievalShadowComparison(int v1CandidateCount, int v2CandidateCount,
        long v1LatencyMs, long v2LatencyMs, double documentOverlap, boolean v2Empty) { }
