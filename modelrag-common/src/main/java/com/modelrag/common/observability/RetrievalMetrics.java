package com.modelrag.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Shared low-cardinality retrieval metrics. Values supplied by callers are normalized to bounded vocabularies. */
public final class RetrievalMetrics {
    private final MeterRegistry registry;

    public RetrievalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(RetrievalTraceContext context, String status) {
        counter("modelrag.retrieval.requests", context, status).increment();
    }

    public void latency(RetrievalTraceContext context, String status, long durationMs) {
        timer("modelrag.retrieval.latency", context, status).record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    public void stage(RetrievalTraceContext context, String stage, String channel, String status, long durationMs) {
        registry.timer("modelrag.retrieval.stage.latency", "mode", mode(context), "stage", stage(stage),
                "channel", channel(channel), "status", status(status), "profile", profile(context))
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    public void candidates(RetrievalTraceContext context, String channel, long count) {
        counter("modelrag.retrieval.candidates", context, "success", channel).increment(Math.max(0, count));
    }

    public void evidence(RetrievalTraceContext context, long count) {
        counter("modelrag.retrieval.evidence.count", context, "success", "evidence").increment(Math.max(0, count));
    }

    public void degraded(RetrievalTraceContext context) {
        counter("modelrag.retrieval.degraded", context, "degraded", "none").increment();
    }

    public void failure(RetrievalTraceContext context) {
        counter("modelrag.retrieval.failures", context, "error", "none").increment();
    }

    public String mode(RetrievalTraceContext context) {
        String value = context == null ? "unknown" : context.mode();
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "V1", "V2", "AGENTIC_RAG" -> value.toUpperCase(Locale.ROOT);
            default -> "unknown";
        };
    }

    private io.micrometer.core.instrument.Counter counter(String name, RetrievalTraceContext context,
            String status) {
        return counter(name, context, status, "none");
    }

    private io.micrometer.core.instrument.Counter counter(String name, RetrievalTraceContext context,
            String status, String channel) {
        return registry.counter(name, "mode", mode(context), "status", status(status), "channel", channel(channel),
                "profile", profile(context));
    }

    private io.micrometer.core.instrument.Timer timer(String name, RetrievalTraceContext context, String status) {
        return registry.timer(name, "mode", mode(context), "status", status(status), "profile", profile(context));
    }

    private String stage(String value) { return bounded(value, "unknown", 32); }
    private String channel(String value) { return bounded(value, "none", 32); }
    private String status(String value) { return bounded(value, "unknown", 32); }
    private String profile(RetrievalTraceContext context) { return bounded(context == null ? null : context.profile(), "default", 64); }
    private String bounded(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return normalized.substring(0, Math.min(max, normalized.length()));
    }
}
