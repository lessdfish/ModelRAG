package com.modelrag.api;

import java.io.InputStream;

public interface DocumentVectorizationTool {
    IngestionResult ingest(IngestionRequest request, InputStream content);

    record IngestionRequest(String userId, long datasetId, String fileName, String declaredMimeType, long size) { }
    record IngestionResult(long documentId, String status, String contentHash, int chunkCount, String errorCode) { }
}
