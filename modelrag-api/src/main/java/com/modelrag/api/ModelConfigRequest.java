package com.modelrag.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModelConfigRequest(
        @NotBlank @Size(max = 40) String modelType,
        @NotBlank @Size(max = 120) String provider,
        @NotBlank @Size(max = 200) String modelName,
        @Size(max = 500) String baseUrl,
        @Size(max = 200) String secretId,
        @Size(max = 2000) String apiKey,
        boolean enabled) { }
