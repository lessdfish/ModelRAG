package com.modelrag.indexing.pipeline;

/** Explicit V1 result used by the dual-write facade. */
public record LegacyIndexResult(boolean success, long indexVersion, int chunkCount, String error) {
    public LegacyIndexResult {
        if (indexVersion < 0 || chunkCount < 0) throw new IllegalArgumentException("V1 索引结果计数无效");
    }
}
