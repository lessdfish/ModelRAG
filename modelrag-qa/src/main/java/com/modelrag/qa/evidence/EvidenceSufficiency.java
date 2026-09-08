package com.modelrag.qa.evidence;

/** Deterministic, non-LLM assessment of whether an EvidenceSet can support an answer. */
public record EvidenceSufficiency(boolean sufficient, double confidence, String reason, double coverage) {
    public EvidenceSufficiency {
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)
                || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("evidence confidence is invalid");
        }
        if (Double.isNaN(coverage) || Double.isInfinite(coverage) || coverage < 0 || coverage > 1) {
            throw new IllegalArgumentException("evidence coverage is invalid");
        }
        reason = reason == null || reason.isBlank() ? "unspecified" : reason;
    }
}
