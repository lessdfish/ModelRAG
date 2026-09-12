package com.modelrag.server.eval;

import java.util.List;

/** Resolved labels used by both evaluation adapters. */
public record EvalLabels(List<Long> expectedChunkIds, List<Long> expectedDocumentIds,
        List<Long> expectedNodeIds, List<EvalEvidenceGroup> expectedEvidenceGroups,
        String documentStatus, String nodeStatus, String evidenceStatus) {
    public static final String COMPARABLE = "COMPARABLE";
    public static final String LEGACY_LABEL_ONLY = "LEGACY_LABEL_ONLY";
    public static final String INSUFFICIENT_LABELS = "INSUFFICIENT_LABELS";
    public static final String INSUFFICIENT_SAMPLE = "INSUFFICIENT_SAMPLE";
    public static final String NON_COMPARABLE = "NON_COMPARABLE";
    public static final String MISSING = "MISSING";
    public static final String V1_ONLY = "V1_ONLY";
    public static final String V2_ONLY = "V2_ONLY";

    public EvalLabels {
        expectedChunkIds = clean(expectedChunkIds);
        expectedDocumentIds = clean(expectedDocumentIds);
        expectedNodeIds = clean(expectedNodeIds);
        expectedEvidenceGroups = expectedEvidenceGroups == null ? List.of() : List.copyOf(expectedEvidenceGroups);
        documentStatus = status(documentStatus);
        nodeStatus = status(nodeStatus);
        evidenceStatus = status(evidenceStatus);
    }

    public String overallStatus() {
        if (LEGACY_LABEL_ONLY.equals(documentStatus) || LEGACY_LABEL_ONLY.equals(nodeStatus)) return LEGACY_LABEL_ONLY;
        if (INSUFFICIENT_LABELS.equals(documentStatus) && INSUFFICIENT_LABELS.equals(nodeStatus)
                && INSUFFICIENT_LABELS.equals(evidenceStatus)) return INSUFFICIENT_LABELS;
        return COMPARABLE;
    }

    private static List<Long> clean(List<Long> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && value > 0).distinct().toList();
    }

    private static String status(String value) {
        return value == null || value.isBlank() ? INSUFFICIENT_LABELS : value;
    }
}
