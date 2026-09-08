package com.modelrag.indexing.outbox;

import java.time.Instant;

/** A V2 lexical projection event; deliberately independent from the chunk-shaped V1 outbox. */
public record RetrievalProjectionOutboxEvent(long id, String eventType, long datasetId, long documentId,
        long documentVersionId, long indexBuildId, long retrievalUnitId, String payload, String status,
        int retryCount, Instant nextRetryAt, Instant leaseUntil, boolean deadLetter, String error,
        String idempotencyKey) {
    public RetrievalProjectionOutboxEvent processing(Instant leaseUntil) {
        return new RetrievalProjectionOutboxEvent(id, eventType, datasetId, documentId, documentVersionId,
                indexBuildId, retrievalUnitId, payload, "PROCESSING", retryCount, nextRetryAt, leaseUntil,
                false, error, idempotencyKey);
    }

    public RetrievalProjectionOutboxEvent done() {
        return new RetrievalProjectionOutboxEvent(id, eventType, datasetId, documentId, documentVersionId,
                indexBuildId, retrievalUnitId, payload, "DONE", retryCount, null, null, false, null,
                idempotencyKey);
    }

    public RetrievalProjectionOutboxEvent failed(String safeError) {
        int nextRetry = retryCount + 1;
        boolean terminal = nextRetry >= 5;
        Instant retryAt = terminal ? null : Instant.now().plusSeconds(1L << Math.min(nextRetry, 6));
        return new RetrievalProjectionOutboxEvent(id, eventType, datasetId, documentId, documentVersionId,
                indexBuildId, retrievalUnitId, payload, terminal ? "FAILED" : "PENDING", nextRetry,
                retryAt, null, terminal, safeError, idempotencyKey);
    }
}
