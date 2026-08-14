package com.modelrag.api;

public interface Retriever {
    RetrievalResult retrieve(RetrievalRequest request);

    record RetrievalRequest(String userId, long datasetId, String question, int topK) { }
    record RetrievalResult(java.util.List<Evidence> evidence, java.util.Set<String> degradedComponents) { }
    record Evidence(long chunkId, long documentId, String documentName, String location, String content, double score) { }
}
