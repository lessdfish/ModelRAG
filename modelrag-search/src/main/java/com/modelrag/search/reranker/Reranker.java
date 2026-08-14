package com.modelrag.search.reranker;

import com.modelrag.search.dto.ScoredChunk;
import java.util.List;
import java.time.Duration;

public interface Reranker {
    List<ScoredChunk> rerank(long datasetId, String query, List<ScoredChunk> candidates);
    default List<ScoredChunk> rerank(long datasetId, String query, List<ScoredChunk> candidates, Duration timeout) {
        return rerank(datasetId, query, candidates);
    }
    default boolean enabled(){return false;}
}
