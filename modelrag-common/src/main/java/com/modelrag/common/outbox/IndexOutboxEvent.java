package com.modelrag.common.outbox;

import java.time.Instant;

public record IndexOutboxEvent(long id, String eventType, long datasetId, long documentId, long chunkId,
                               String payload, String status, int retryCount, Instant nextRetryAt, String error) {
    public IndexOutboxEvent processing() {
        return new IndexOutboxEvent(id, eventType, datasetId, documentId, chunkId, payload, "PROCESSING", retryCount, nextRetryAt, error);
    }

    public IndexOutboxEvent done() {
        return new IndexOutboxEvent(id, eventType, datasetId, documentId, chunkId, payload, "DONE", retryCount, null, null);
    }

    public IndexOutboxEvent failed(String reason) {
        int retry = retryCount + 1;
        return new IndexOutboxEvent(id, eventType, datasetId, documentId, chunkId, payload, retry >= 5 ? "FAILED" : "PENDING", retry, Instant.now().plusSeconds(1L << Math.min(retry, 6)), reason);
    }
}
