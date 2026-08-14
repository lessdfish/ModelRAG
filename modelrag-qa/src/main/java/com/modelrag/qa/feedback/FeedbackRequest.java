package com.modelrag.qa.feedback;

import jakarta.validation.constraints.NotBlank;

public record FeedbackRequest(@NotBlank String traceId, @NotBlank String rating, String comment) {
}
