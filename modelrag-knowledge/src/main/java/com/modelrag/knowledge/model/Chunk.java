package com.modelrag.knowledge.model;
import java.util.Map;
public record Chunk(long id, long documentId, long datasetId, int index, String content, Map<String, String> metadata, Long parentChunkId) {
    public Chunk(long id, long documentId, long datasetId, int index, String content, Map<String, String> metadata) {
        this(id, documentId, datasetId, index, content, metadata, null);
    }
}
