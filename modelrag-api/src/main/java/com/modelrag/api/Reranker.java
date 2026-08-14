package com.modelrag.api;

public interface Reranker {
    java.util.List<Retriever.Evidence> rerank(String question, java.util.List<Retriever.Evidence> candidates,
            java.time.Duration timeout);
}
