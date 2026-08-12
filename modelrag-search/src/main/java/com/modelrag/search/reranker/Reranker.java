package com.modelrag.search.reranker;

import com.modelrag.search.dto.ScoredChunk;
import java.util.List;

public interface Reranker {
    List<ScoredChunk> rerank(long datasetId, String query, List<ScoredChunk> candidates);
    default boolean enabled(){return false;}
}
