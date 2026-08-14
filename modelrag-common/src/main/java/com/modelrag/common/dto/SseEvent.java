package com.modelrag.common.dto;

import java.util.Map;

public record SseEvent(String type, String message, Map<String, Object> data) {
}
