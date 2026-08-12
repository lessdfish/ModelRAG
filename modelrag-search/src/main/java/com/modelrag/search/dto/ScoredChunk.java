package com.modelrag.search.dto;
public record ScoredChunk(long chunkId, String content, double score, String channel, int rank) { }
