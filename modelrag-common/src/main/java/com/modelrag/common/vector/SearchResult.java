package com.modelrag.common.vector;
public record SearchResult(long chunkId, String content, double score, String channel) { }
