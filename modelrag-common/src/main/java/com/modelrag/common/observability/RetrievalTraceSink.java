package com.modelrag.common.observability;

import java.util.List;
import java.util.Map;

/** Narrow persistence port for bounded retrieval telemetry. */
public interface RetrievalTraceSink {
    RetrievalTraceSink NOOP = new RetrievalTraceSink() {
        @Override public long recordAction(RetrievalTraceContext context, Action action) { return action.stepNo(); }
    };

    default void start(RetrievalTraceContext context, String querySummary) { }

    default long recordAction(RetrievalTraceContext context, Action action) { return action.stepNo(); }

    default void recordEvidence(RetrievalTraceContext context, Evidence evidence) { }

    default void complete(RetrievalTraceContext context, Completion completion) { }

    default void fail(RetrievalTraceContext context, String safeError, Completion completion) { }

    record Action(int stepNo, String actionType, String channel, Map<String, ?> requestSummary,
            Map<String, ?> resultSummary, long latencyMs, int candidateCount, boolean degraded,
            List<String> degradedComponents) {
        public Action {
            requestSummary = BoundedTracePayload.safeSummary(requestSummary);
            resultSummary = BoundedTracePayload.safeSummary(resultSummary);
            degradedComponents = BoundedTracePayload.safeExcerptList(degradedComponents);
        }
    }

    record Evidence(long actionId, long datasetId, Long documentId, Long documentVersionId, Long nodeId,
            Long retrievalUnitId, String channel, Double score, Integer rank, boolean selected, String excerpt,
            Map<String, ?> locator) {
        public Evidence {
            channel = channel == null || channel.isBlank() ? "unknown" : channel;
            excerpt = BoundedTracePayload.safeExcerpt(excerpt);
            locator = BoundedTracePayload.safeLocator(locator);
        }
    }

    record Completion(long totalActions, long totalEvidence, long latencyMs, boolean refused,
            List<String> degradedComponents) {
        public Completion {
            degradedComponents = BoundedTracePayload.safeExcerptList(degradedComponents);
        }
    }
}
