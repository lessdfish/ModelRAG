package com.modelrag.knowledge.model;
public record Dataset(long id, String name, String description, int chunkSize, int chunkOverlap, int topK, double threshold, long revision) { }
