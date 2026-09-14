package com.modelrag.api;

/** Optional measured embedding boundary used by retrieval performance diagnostics. */
public interface DiagnosticTextEmbeddingProvider extends TextEmbeddingProvider {
    EmbeddingInvocation embedWithDiagnostics(long datasetId, String text);

    record EmbeddingInvocation(float[] vector, double cacheLookupMs, double remoteCallMs,
            double totalMs, String cacheOutcome) { }
}
