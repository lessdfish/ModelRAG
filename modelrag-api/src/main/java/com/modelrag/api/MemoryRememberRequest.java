package com.modelrag.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemoryRememberRequest(
        Long datasetId,
        @NotBlank @Size(max = 2_000) String content,
        @Size(max = 80) String type,
        @Size(max = 200) String memoryKey,
        boolean confirmed) { }
