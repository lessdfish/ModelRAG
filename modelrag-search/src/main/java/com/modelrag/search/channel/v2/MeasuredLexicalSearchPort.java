package com.modelrag.search.channel.v2;

/** Optional timing detail supplied by the production lexical adapter. */
public interface MeasuredLexicalSearchPort extends LexicalSearchPort {
    MeasuredResult searchMeasured(LexicalSearchRequest request, boolean overflow);

    record MeasuredResult(ActiveValidatedResult result, long elasticsearchNanos,
            long activeValidationNanos) { }
}
