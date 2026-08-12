package com.modelrag.search.dto;

import java.util.List;

public record QueryExtensionResult(String originalQuery, String rewrittenQuery, List<String> searchQueries, String rerankQuery) { }
