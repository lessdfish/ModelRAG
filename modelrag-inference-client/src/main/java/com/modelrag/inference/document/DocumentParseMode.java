package com.modelrag.inference.document;

import java.util.Locale;

/** Explicit routing policy for a document type. */
public enum DocumentParseMode {
    LOCAL_ONLY,
    LOCAL_FIRST,
    REMOTE_FIRST,
    REMOTE_ONLY;

    public static DocumentParseMode parse(String value, DocumentParseMode fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
