package com.modelrag.common.observability;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Best-effort trace session; telemetry failures never change retrieval behavior. */
public final class RetrievalTraceSession {
    private final RetrievalTraceSink sink;
    private final RetrievalTraceContext context;
    private final AtomicInteger actions = new AtomicInteger();
    private final AtomicInteger evidence = new AtomicInteger();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final long started = System.nanoTime();

    private RetrievalTraceSession(RetrievalTraceSink sink, RetrievalTraceContext context, String querySummary) {
        this.sink = sink == null ? RetrievalTraceSink.NOOP : sink;
        this.context = context;
        safely(() -> this.sink.start(context, querySummary));
    }

    public static RetrievalTraceSession start(RetrievalTraceSink sink, RetrievalTraceContext context,
            String querySummary) {
        return new RetrievalTraceSession(sink, context, querySummary);
    }

    public long action(String actionType, String channel, java.util.Map<String, ?> requestSummary,
            java.util.Map<String, ?> resultSummary, long latencyMs, int candidateCount, boolean degraded,
            List<String> degradedComponents) {
        if (terminal.get()) return actions.get();
        int step = actions.incrementAndGet();
        RetrievalTraceSink.Action action = new RetrievalTraceSink.Action(step, actionType, channel,
                BoundedTracePayload.safeSummary(requestSummary), BoundedTracePayload.safeSummary(resultSummary),
                Math.max(0, latencyMs), Math.max(0, candidateCount), degraded,
                BoundedTracePayload.safeExcerptList(degradedComponents));
        final long[] id = {step};
        safely(() -> id[0] = sink.recordAction(context, action));
        return id[0] <= 0 ? step : id[0];
    }

    public void evidence(long actionId, RetrievalTraceSink.Evidence item) {
        if (terminal.get() || item == null) return;
        evidence.incrementAndGet();
        safely(() -> sink.recordEvidence(context, new RetrievalTraceSink.Evidence(actionId, item.datasetId(),
                item.documentId(), item.documentVersionId(), item.nodeId(), item.retrievalUnitId(), item.channel(),
                item.score(), item.rank(), item.selected(), BoundedTracePayload.safeExcerpt(item.excerpt()),
                BoundedTracePayload.safeLocator(item.locator()))));
    }

    public void complete(boolean refused, List<String> degradedComponents) {
        if (!terminal.compareAndSet(false, true)) return;
        safely(() -> sink.complete(context, new RetrievalTraceSink.Completion(actions.get(), evidence.get(),
                elapsed(), refused, degradedComponents)));
    }

    public void fail(String safeError, List<String> degradedComponents) {
        if (!terminal.compareAndSet(false, true)) return;
        String boundedError = BoundedTracePayload.safeExcerpt(safeError);
        safely(() -> sink.fail(context, boundedError, new RetrievalTraceSink.Completion(actions.get(), evidence.get(),
                elapsed(), true, degradedComponents)));
    }

    public RetrievalTraceContext context() { return context; }
    public int actionCount() { return actions.get(); }
    public int evidenceCount() { return evidence.get(); }

    private long elapsed() { return (System.nanoTime() - started) / 1_000_000; }

    private void safely(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { }
    }
}
