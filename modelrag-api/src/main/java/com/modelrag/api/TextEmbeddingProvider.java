package com.modelrag.api;

import java.util.List;

/** Stable text-embedding boundary used outside the indexing implementation module. */
public interface TextEmbeddingProvider {
    float[] embed(long datasetId, String text);

    default List<float[]> embedBatch(long datasetId, List<String> texts) {
        return texts == null ? List.of() : texts.stream().map(text -> embed(datasetId, text)).toList();
    }
}
