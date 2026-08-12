package com.modelrag.common.vector;
import java.util.Map;
public record VectorDocument(long id, long documentId, long datasetId, String content, float[] embedding, Map<String, String> metadata) { }
