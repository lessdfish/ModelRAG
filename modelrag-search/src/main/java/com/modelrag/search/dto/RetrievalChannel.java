package com.modelrag.search.dto;

/** Retrieval channel used by the V2 projection-centric search path. */
public enum RetrievalChannel {
    SEMANTIC,
    LEXICAL,
    FUSED,
    RERANK
}
