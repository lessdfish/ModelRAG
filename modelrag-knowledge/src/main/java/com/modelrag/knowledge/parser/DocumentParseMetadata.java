package com.modelrag.knowledge.parser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Parser provenance and bounded-quality metadata for a V2 structured parse. */
public record DocumentParseMetadata(String parserName, String parserVersion, String parserQuality,
        boolean layoutPreserved, Map<String, Object> attributes) {
    public DocumentParseMetadata {
        if (parserName == null || parserName.isBlank() || parserVersion == null || parserVersion.isBlank()) {
            throw new IllegalArgumentException("解析器名称和版本不能为空");
        }
        parserQuality = parserQuality == null || parserQuality.isBlank() ? "HEURISTIC" : parserQuality;
        attributes = attributes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public DocumentParseMetadata(String parserName, String parserVersion, Map<String, Object> attributes) {
        this(parserName, parserVersion, "HEURISTIC", false, attributes);
    }
}
