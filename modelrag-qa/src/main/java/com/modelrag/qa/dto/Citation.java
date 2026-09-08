package com.modelrag.qa.dto;

import com.modelrag.qa.evidence.Evidence;

/** Stable evidence reference containing enough metadata to reproduce the cited source. */
public record Citation(long chunkId, long documentId, String documentName, String location,
        long indexVersion, String excerpt, double score, String citationId,
        Long documentVersionId, Long nodeId, Long retrievalUnitId, Long indexBuildId,
        String titlePath, Integer pageFrom, Integer pageTo) {
    public Citation(long chunkId, long documentId, String documentName, String location,
            long indexVersion, String excerpt, double score) {
        this(chunkId, documentId, documentName, location, indexVersion, excerpt, score,
                null, null, null, null, null, "", null, null);
    }

    public Citation(long chunkId, String excerpt, double score) {
        this(chunkId, 0, "", "", 0, excerpt, score, null, null, null, null, null, "", null, null);
    }

    public static Citation fromEvidence(Evidence evidence) {
        if (evidence == null || !evidence.primary()) {
            throw new IllegalArgumentException("only primary evidence can become a citation");
        }
        String location = evidence.titlePath();
        if (evidence.locator().pageFrom() != null) {
            String page = evidence.locator().pageFrom().equals(evidence.locator().pageTo())
                    ? String.valueOf(evidence.locator().pageFrom())
                    : evidence.locator().pageFrom() + "-" + evidence.locator().pageTo();
            location = location == null || location.isBlank() ? "第 " + page + " 页"
                    : "第 " + page + " 页 · " + location;
        }
        return new Citation(0, evidence.documentId(), evidence.documentName(), location == null ? "" : location,
                evidence.documentVersionId(), excerpt(evidence.content()), evidence.score(), evidence.evidenceId(),
                evidence.documentVersionId(), evidence.nodeId(), evidence.retrievalUnitId(), evidence.indexBuildId(),
                evidence.titlePath(), evidence.locator().pageFrom(), evidence.locator().pageTo());
    }

    public boolean v2() {
        return citationId != null && !citationId.isBlank() && documentVersionId != null && nodeId != null;
    }

    private static String excerpt(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
