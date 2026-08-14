package com.modelrag.agent.controller;

import jakarta.validation.constraints.Size;

public record ConversationCreateRequest(@Size(max = 200) String title, Long datasetId) {
    public String safeTitle() { return title == null || title.isBlank() ? "新会话" : title.trim(); }
}
