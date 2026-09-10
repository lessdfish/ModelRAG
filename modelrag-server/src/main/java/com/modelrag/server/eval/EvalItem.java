package com.modelrag.server.eval;
import java.util.List;
public record EvalItem(Long id, String question, List<Long> expectedChunkIds, List<Long> expectedDocumentIds,
        List<Long> expectedNodeIds, List<EvalEvidenceGroup> expectedEvidenceGroups, String expectedAnswer,
        Boolean shouldRefuse, String category, String sourceTraceId, String failureStage) {
    public EvalItem {
        expectedChunkIds = clean(expectedChunkIds);
        expectedDocumentIds = clean(expectedDocumentIds);
        expectedNodeIds = clean(expectedNodeIds);
        expectedEvidenceGroups = expectedEvidenceGroups == null ? List.of() : List.copyOf(expectedEvidenceGroups);
        category = normalizeCategory(category);
    }

    public EvalItem(Long id, String question, List<Long> expectedChunkIds, String expectedAnswer,
            Boolean shouldRefuse, String category, String sourceTraceId, String failureStage) {
        this(id, question, expectedChunkIds, List.of(), List.of(), List.of(), expectedAnswer, shouldRefuse,
                category, sourceTraceId, failureStage);
    }

    public EvalItem(Long id, String question, List<Long> expectedChunkIds, List<Long> expectedDocumentIds,
            List<Long> expectedNodeIds, List<EvalEvidenceGroup> expectedEvidenceGroups, String expectedAnswer,
            Boolean shouldRefuse, String category) {
        this(id, question, expectedChunkIds, expectedDocumentIds, expectedNodeIds, expectedEvidenceGroups,
                expectedAnswer, shouldRefuse, category, null, null);
    }

    public EvalItem(String question, List<Long> expectedChunkIds, String expectedAnswer,
            Boolean shouldRefuse, String category) {
        this(null, question, expectedChunkIds, expectedAnswer, shouldRefuse, category, null, null);
    }

    public EvalItem(String question, List<Long> expectedChunkIds) {
        this(null, question, expectedChunkIds, null, false, null, null, null);
    }

    public EvalItem withId(Long id) {
        return new EvalItem(id, question, expectedChunkIds, expectedDocumentIds, expectedNodeIds,
                expectedEvidenceGroups, expectedAnswer, shouldRefuse, category, sourceTraceId, failureStage);
    }

    private static List<Long> clean(List<Long> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && value > 0).distinct().toList();
    }

    private static String normalizeCategory(String value) {
        if (value == null || value.isBlank()) return EvalCategory.POINT_FACT.name();
        String trimmed = value.trim();
        return EvalCategory.isCanonical(trimmed) ? trimmed.toUpperCase() : trimmed;
    }
}
