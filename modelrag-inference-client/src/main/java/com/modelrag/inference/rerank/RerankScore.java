package com.modelrag.inference.rerank;

/** A score keyed by the stable candidate id, never by response position. */
public record RerankScore(String id, double score) { }
