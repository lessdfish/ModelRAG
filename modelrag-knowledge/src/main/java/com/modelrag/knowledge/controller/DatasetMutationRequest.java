package com.modelrag.knowledge.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DatasetMutationRequest(@NotBlank @Size(max = 200) String name,
        @Size(max = 2_000) String description, Integer chunkSize, Integer chunkOverlap,
        Integer topK, Double threshold, Double similarityThreshold) {
    public Double thresholdValue() { return threshold != null ? threshold : similarityThreshold; }
}
