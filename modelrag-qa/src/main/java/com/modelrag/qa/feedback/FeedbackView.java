package com.modelrag.qa.feedback;

import java.time.Instant;

/** Immutable public representation of feedback. */
public record FeedbackView(long id, String traceId, long datasetId, String userId, String rating,
        String comment, Instant createdAt) {
}
