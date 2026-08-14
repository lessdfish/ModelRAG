package com.modelrag.qa.dto;

/** Stable evidence reference containing enough metadata to reproduce the cited source. */
public record Citation(long chunkId, long documentId, String documentName, String location,
        long indexVersion, String excerpt, double score) {
    public Citation(long chunkId, String excerpt, double score) {
        this(chunkId, 0, "", "", 0, excerpt, score);
    }
}
