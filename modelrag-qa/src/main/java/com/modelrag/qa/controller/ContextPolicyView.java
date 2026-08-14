package com.modelrag.qa.controller;

/** Stable, typed description of the context assembly policy exposed to clients. */
public record ContextPolicyView(
        int maxEvidenceTokens,
        int topK,
        int summaryAfterMessages,
        int recentMessages) {
}
