package com.modelrag.api;

public record RetrievalPolicy(double vectorWeight, double bm25Weight, int topK, double similarityThreshold,
        boolean requireRerank) {
    public static RetrievalPolicy defaults() {
        return new RetrievalPolicy(.7, .3, 5, .7, false);
    }
}
