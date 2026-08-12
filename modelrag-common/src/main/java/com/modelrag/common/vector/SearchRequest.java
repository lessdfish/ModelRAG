package com.modelrag.common.vector;
public record SearchRequest(long datasetId, float[] embedding, int topK) { }
