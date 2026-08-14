package com.modelrag.knowledge.parser;

/** Hard limits protecting the parser process from oversized or hostile documents. */
public record ParseLimits(long maxPages, int maxExtractedChars) {
    public ParseLimits {
        if (maxPages < 1 || maxExtractedChars < 1) {
            throw new IllegalArgumentException("解析限制必须为正数");
        }
    }

    public static ParseLimits defaults() {
        return new ParseLimits(1_000, 5_000_000);
    }
}
