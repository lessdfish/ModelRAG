package com.modelrag.inference.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded OCR text unit with optional normalized bounding-box coordinates. */
public record OcrBlock(String text, double confidence,
        @JsonProperty("bounding_box") Map<String, Object> boundingBox) {
    public OcrBlock {
        if (text == null || text.length() > 100_000 || !Double.isFinite(confidence)
                || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("OCR block is invalid");
        }
        text = text.trim();
        boundingBox = boundingBox == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(boundingBox));
    }
}
