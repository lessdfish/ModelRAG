package com.modelrag.server.eval;

import java.util.List;

/** Canonical G11 evaluation categories. Legacy/custom categories remain readable for old datasets. */
public enum EvalCategory {
    POINT_FACT,
    CROSS_SECTION,
    CROSS_DOCUMENT,
    MULTI_HOP,
    WHOLE_DOCUMENT,
    GLOBAL,
    TABLE,
    VERSION_SENSITIVE,
    REFERENCE,
    REFUSAL;

    public static List<String> canonicalNames() {
        return List.of(values()).stream().map(Enum::name).toList();
    }

    public static boolean isCanonical(String value) {
        if (value == null) return false;
        try {
            valueOf(value.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
