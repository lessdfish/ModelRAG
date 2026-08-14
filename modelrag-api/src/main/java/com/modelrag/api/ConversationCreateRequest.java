package com.modelrag.api;

import jakarta.validation.constraints.Size;

public record ConversationCreateRequest(Long datasetId, @Size(max = 200) String title) { }
