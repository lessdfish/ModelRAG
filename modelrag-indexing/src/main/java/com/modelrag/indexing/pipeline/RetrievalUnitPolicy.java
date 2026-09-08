package com.modelrag.indexing.pipeline;

/** Bounded deterministic policy for turning one node into retrieval projections. */
public record RetrievalUnitPolicy(int maxUnitChars, int overlapChars, int maxUnitsPerNode, int maxUnitsPerBuild) {
    public RetrievalUnitPolicy {
        if (maxUnitChars < 1 || overlapChars < 0 || overlapChars >= maxUnitChars
                || maxUnitsPerNode < 1 || maxUnitsPerBuild < 1 || maxUnitsPerNode > maxUnitsPerBuild) {
            throw new IllegalArgumentException("检索单元策略无效");
        }
    }

    public static RetrievalUnitPolicy defaults() { return new RetrievalUnitPolicy(4_000, 200, 128, 100_000); }
}
