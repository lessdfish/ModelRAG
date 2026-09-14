package com.modelrag.search.dto;

import java.util.List;

public record QueryExtensionResult(String originalQuery, String rewrittenQuery, String semanticQuery,
        List<String> lexicalQueries, String rerankQuery) {
    public QueryExtensionResult {
        lexicalQueries = lexicalQueries == null ? List.of() : List.copyOf(lexicalQueries);
    }

    /** Keeps the unchanged V1 search path on its existing lexical expansion contract. */
    public List<String> searchQueries() {
        return lexicalQueries;
    }
}
