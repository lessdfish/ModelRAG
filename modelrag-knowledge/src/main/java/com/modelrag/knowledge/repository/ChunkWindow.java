package com.modelrag.knowledge.repository;

/** A bounded inclusive chunk-index window for one document. */
public record ChunkWindow(long documentId, int fromIndex, int toIndex) {
    public ChunkWindow {
        if (fromIndex > toIndex) throw new IllegalArgumentException("chunk window is inverted");
    }
}
