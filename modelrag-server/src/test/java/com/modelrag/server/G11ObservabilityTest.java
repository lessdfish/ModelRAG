package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.observability.BoundedTracePayload;
import com.modelrag.common.observability.RetrievalMetrics;
import com.modelrag.common.observability.RetrievalTraceContext;
import com.modelrag.common.observability.RetrievalTraceSession;
import com.modelrag.common.observability.RetrievalTraceSink;
import com.modelrag.common.observability.TraceCorrelation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class G11ObservabilityTest {
    @Test
    void tracePayloadDropsSecretsVectorsPromptsAndState() throws Exception {
        Map<String, Object> safe = BoundedTracePayload.safeSummary(Map.of(
                "stage", "semantic", "candidateCount", 4, "query", "full user question",
                "vector", List.of(1, 2), "prompt", "full prompt", "secret", "password", "state", Map.of("x", 1)));
        assertEquals("semantic", safe.get("stage"));
        assertEquals(4, safe.get("candidateCount"));
        assertFalse(safe.containsKey("query"));
        assertFalse(safe.containsKey("vector"));
        assertFalse(safe.containsKey("prompt"));
        assertFalse(safe.containsKey("secret"));
        assertFalse(safe.containsKey("state"));
        String excerpt = BoundedTracePayload.safeExcerpt("api_key=top-secret Bearer abc123");
        assertTrue(excerpt.contains("[REDACTED]"));
        assertTrue(excerpt.length() <= BoundedTracePayload.MAX_EXCERPT_CHARS);
        assertTrue(new ObjectMapper().writeValueAsString(safe).length() <= BoundedTracePayload.MAX_JSON_CHARS);
    }

    @Test
    void oneSessionCorrelatesAsyncWorkAndBoundsCounters() throws Exception {
        CapturingSink sink = new CapturingSink();
        RetrievalTraceContext context = new RetrievalTraceContext("request-1", "trace-1", "execution-1",
                "V2", 7, "user-1", "qwen3-v1");
        RetrievalTraceSession session = RetrievalTraceSession.start(sink, context, "redacted");
        try (TraceCorrelation.Scope ignored = TraceCorrelation.bind(context, session)) {
            long action = session.action("SEMANTIC_SEARCH", "semantic", Map.of("stage", "semantic"),
                    Map.of("candidateCount", 1), 2, 1, false, List.of());
            CompletableFuture<Void> future = CompletableFuture.runAsync(TraceCorrelation.wrap(context, session,
                    () -> {
                        assertEquals("request-1", TraceCorrelation.current().requestId());
                        assertEquals("trace-1", TraceCorrelation.current().traceId());
                        assertEquals(session, TraceCorrelation.currentSession());
                        session.evidence(action, new RetrievalTraceSink.Evidence(action, 7, 8L, 9L, 10L,
                                11L, "semantic", .8, 1, true, "bounded evidence", Map.of()));
                    }));
            future.get();
            session.complete(false, List.of());
        }
        assertEquals(1, sink.starts);
        assertEquals(1, sink.actions.size());
        assertEquals(1, sink.evidence.size());
        assertEquals(1, sink.completions);
    }

    @Test
    void retrievalMetricsUseOnlyLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RetrievalMetrics metrics = new RetrievalMetrics(registry);
        RetrievalTraceContext context = new RetrievalTraceContext("request-1", "trace-1", "execution-1",
                "AGENTIC_RAG", 7, "user-1", "qwen3-v1");
        metrics.request(context, "success");
        var counter = registry.find("modelrag.retrieval.requests").counter();
        assertEquals(1, counter.count());
        assertEquals(List.of("channel", "mode", "profile", "status"),
                counter.getId().getTags().stream().map(tag -> tag.getKey()).sorted().toList());
        assertFalse(counter.getId().getTags().stream().anyMatch(tag -> tag.getKey().contains("user")
                || tag.getKey().contains("dataset") || tag.getKey().contains("trace")));
    }

    private static final class CapturingSink implements RetrievalTraceSink {
        int starts;
        int completions;
        final List<Action> actions = new ArrayList<>();
        final List<Evidence> evidence = new ArrayList<>();

        @Override public void start(RetrievalTraceContext context, String querySummary) { starts++; }
        @Override public long recordAction(RetrievalTraceContext context, Action action) {
            actions.add(action); return actions.size();
        }
        @Override public void recordEvidence(RetrievalTraceContext context, Evidence value) { evidence.add(value); }
        @Override public void complete(RetrievalTraceContext context, Completion completion) { completions++; }
    }
}
