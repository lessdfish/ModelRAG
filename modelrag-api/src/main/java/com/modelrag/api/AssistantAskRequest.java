package com.modelrag.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Versioned HTTP-neutral request shared by the API facade and future SDK. */
public record AssistantAskRequest(
        @Positive long datasetId,
        @NotBlank @Size(max = 20_000) String question,
        Long conversationId,
        boolean agent) { }
