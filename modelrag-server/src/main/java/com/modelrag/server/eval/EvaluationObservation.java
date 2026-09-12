package com.modelrag.server.eval;

import java.util.List;
import java.util.Map;

/** Adapter-neutral observation of one real V1 or V2 evaluation execution. */
public record EvaluationObservation(
        String variant,
        String traceId,
        List<Long> finalChunkIds,
        List<Long> fusedChunkIds,
        List<Long> finalDocumentIds,
        List<Long> fusedDocumentIds,
        List<Long> finalNodeIds,
        List<Long> fusedNodeIds,
        List<Long> finalRetrievalUnitIds,
        List<Long> fusedRetrievalUnitIds,
        List<Long> semanticResultIds,
        List<Long> lexicalResultIds,
        List<Long> rerankedResultIds,
        String answer,
        boolean refused,
        boolean error,
        boolean degraded,
        long latencyMs,
        int actionCount,
        int navigationCount,
        Long inputTokens,
        Long outputTokens,
        Map<String, Long> stageLatencyMs,
        List<ObservedEvidenceIdentity> evidenceIdentities) {
    public EvaluationObservation {
        variant = variant == null || variant.isBlank() ? "UNKNOWN" : variant;
        traceId = traceId == null ? "" : traceId;
        finalChunkIds = clean(finalChunkIds);
        fusedChunkIds = clean(fusedChunkIds);
        finalDocumentIds = clean(finalDocumentIds);
        fusedDocumentIds = clean(fusedDocumentIds);
        finalNodeIds = clean(finalNodeIds);
        fusedNodeIds = clean(fusedNodeIds);
        finalRetrievalUnitIds = clean(finalRetrievalUnitIds);
        fusedRetrievalUnitIds = clean(fusedRetrievalUnitIds);
        semanticResultIds = clean(semanticResultIds);
        lexicalResultIds = clean(lexicalResultIds);
        rerankedResultIds = clean(rerankedResultIds);
        answer = answer == null ? "" : answer;
        latencyMs = Math.max(0, latencyMs);
        actionCount = Math.max(0, actionCount);
        navigationCount = Math.max(0, navigationCount);
        stageLatencyMs = stageLatencyMs == null ? Map.of() : Map.copyOf(stageLatencyMs);
        evidenceIdentities = evidenceIdentities == null ? List.of()
                : evidenceIdentities.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Compatibility constructor for observations created before tuple evidence identities were exposed. */
    public EvaluationObservation(String variant, String traceId, List<Long> finalChunkIds,
            List<Long> fusedChunkIds, List<Long> finalDocumentIds, List<Long> fusedDocumentIds,
            List<Long> finalNodeIds, List<Long> fusedNodeIds, List<Long> finalRetrievalUnitIds,
            List<Long> fusedRetrievalUnitIds, List<Long> semanticResultIds, List<Long> lexicalResultIds,
            List<Long> rerankedResultIds, String answer, boolean refused, boolean error, boolean degraded,
            long latencyMs, int actionCount, int navigationCount, Long inputTokens, Long outputTokens,
            Map<String, Long> stageLatencyMs) {
        this(variant, traceId, finalChunkIds, fusedChunkIds, finalDocumentIds, fusedDocumentIds,
                finalNodeIds, fusedNodeIds, finalRetrievalUnitIds, fusedRetrievalUnitIds, semanticResultIds,
                lexicalResultIds, rerankedResultIds, answer, refused, error, degraded, latencyMs, actionCount,
                navigationCount, inputTokens, outputTokens, stageLatencyMs, List.of());
    }

    /** Compatibility constructor for observations created before channel result identities were exposed. */
    public EvaluationObservation(String variant, String traceId, List<Long> finalChunkIds,
            List<Long> fusedChunkIds, List<Long> finalDocumentIds, List<Long> fusedDocumentIds,
            List<Long> finalNodeIds, List<Long> fusedNodeIds, List<Long> finalRetrievalUnitIds,
            List<Long> fusedRetrievalUnitIds, String answer, boolean refused, boolean error,
            boolean degraded, long latencyMs, int actionCount, int navigationCount, Long inputTokens,
            Long outputTokens, Map<String, Long> stageLatencyMs) {
        this(variant, traceId, finalChunkIds, fusedChunkIds, finalDocumentIds, fusedDocumentIds,
                finalNodeIds, fusedNodeIds, finalRetrievalUnitIds, fusedRetrievalUnitIds, List.of(), List.of(),
                List.of(), answer, refused, error, degraded, latencyMs, actionCount, navigationCount,
                inputTokens, outputTokens, stageLatencyMs, List.of());
    }

    private static List<Long> clean(List<Long> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && value > 0).distinct().toList();
    }
}
