package com.modelrag.search.orchestrator;

import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.common.observability.RetrievalMetrics;
import com.modelrag.common.observability.RetrievalTraceContext;
import com.modelrag.common.observability.RetrievalTraceSession;
import com.modelrag.common.observability.RetrievalTraceSink;
import com.modelrag.common.observability.TraceCorrelation;
import com.modelrag.search.channel.v2.ActiveBuildScope;
import com.modelrag.search.channel.v2.ActiveBuildScopeResolver;
import com.modelrag.search.channel.v2.LexicalSearchPort;
import com.modelrag.search.channel.v2.LexicalSearchRequest;
import com.modelrag.search.channel.v2.SemanticSearchPort;
import com.modelrag.search.channel.v2.SemanticSearchRequest;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.reranker.Reranker;
import com.modelrag.search.rewrite.QueryRewriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** V2-only hybrid retrieval; V1 SearchOrchestrator remains the production read path. */
@Service
@Profile("!test")
public class HybridRetrievalService {
    private static final int RRF_K = 60;
    private static final int MAX_RECALL = 500;

    private final SemanticSearchPort semantic;
    private final LexicalSearchPort lexical;
    private final TextEmbeddingProvider embeddings;
    private final QueryRewriter rewriter;
    private final Reranker reranker;
    private final RetrievalCandidateReranker candidateReranker;
    private final ActiveBuildScopeResolver activeBuilds;
    private final double semanticWeight;
    private final double lexicalWeight;
    private final long channelTimeoutMs;
    private final long rerankTimeoutMs;
    private final MeterRegistry metrics;
    private final Executor semanticExecutor;
    private final Executor lexicalExecutor;
    private final Executor rerankExecutor;
    private final RetrievalMetrics retrievalMetrics;
    private volatile RetrievalTraceSink traceSink = RetrievalTraceSink.NOOP;

    @Autowired
    public HybridRetrievalService(SemanticSearchPort semantic, LexicalSearchPort lexical,
            TextEmbeddingProvider embeddings, QueryRewriter rewriter, Reranker reranker,
            ActiveBuildScopeResolver activeBuilds,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.rrf-vector-weight:.7}") double semanticWeight,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.rrf-bm25-weight:.3}") double lexicalWeight,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.channel-timeout-ms:800}") long channelTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.rerank-timeout-ms:500}") long rerankTimeoutMs,
            MeterRegistry metrics, @Qualifier("vectorSearchExecutor") Executor semanticExecutor,
            @Qualifier("bm25SearchExecutor") Executor lexicalExecutor,
            @Qualifier("rerankExecutor") Executor rerankExecutor) {
        this.semantic = semantic;
        this.lexical = lexical;
        this.embeddings = embeddings;
        this.rewriter = rewriter;
        this.reranker = reranker;
        this.candidateReranker = new RetrievalCandidateReranker(reranker);
        this.activeBuilds = activeBuilds;
        this.semanticWeight = semanticWeight;
        this.lexicalWeight = lexicalWeight;
        this.channelTimeoutMs = Math.max(50, channelTimeoutMs);
        this.rerankTimeoutMs = Math.max(50, rerankTimeoutMs);
        this.metrics = metrics;
        this.semanticExecutor = semanticExecutor;
        this.lexicalExecutor = lexicalExecutor;
        this.rerankExecutor = rerankExecutor;
        this.retrievalMetrics = new RetrievalMetrics(metrics);
    }

    @Autowired(required = false)
    public void setRetrievalTraceSink(RetrievalTraceSink traceSink) {
        this.traceSink = traceSink == null ? RetrievalTraceSink.NOOP : traceSink;
    }

    public RetrievalV2Stages inspect(RetrievalV2Request request) {
        RetrievalTraceContext context = TraceCorrelation.current();
        if (context == null) context = RetrievalTraceContext.create("V2", request.datasetId(), null,
                request.embeddingProfile());
        RetrievalTraceSession session = TraceCorrelation.currentSession();
        boolean ownsSession = session == null;
        if (ownsSession) session = RetrievalTraceSession.start(traceSink, context, "redacted");
        try (TraceCorrelation.Scope ignored = TraceCorrelation.bind(context, session)) {
            RetrievalV2Stages result = inspectInternal(request);
            String status = result.degradedComponents().isEmpty() ? "success" : "degraded";
            retrievalMetrics.request(context, status);
            retrievalMetrics.latency(context, status, result.latencyMs().getOrDefault("total", 0L));
            if (!result.degradedComponents().isEmpty()) retrievalMetrics.degraded(context);
            if (ownsSession) session.complete(false, result.degradedComponents());
            return result;
        } catch (RuntimeException error) {
            retrievalMetrics.failure(context);
            if (ownsSession) session.fail("retrieval-failure", List.of("RETRIEVAL_FAILURE"));
            throw error;
        }
    }

    private RetrievalV2Stages inspectInternal(RetrievalV2Request request) {
        long started = System.nanoTime();
        var expanded = rewriter.expand(request.query());
        Queue<String> degraded = new ConcurrentLinkedQueue<>();
        Map<String, Long> latency = new ConcurrentHashMap<>();
        int recall = Math.min(MAX_RECALL, Math.max(request.topK() * 2, 10));

        CompletableFuture<List<RetrievalCandidate>> semanticFuture = submitSemantic(request, expanded.searchQueries(),
                recall, degraded, latency);
        ActiveBuildScope scope;
        try {
            scope = activeBuilds.resolve(request.datasetId());
        } catch (RuntimeException error) {
            scope = new ActiveBuildScope(List.of(), true);
        }
        CompletableFuture<List<RetrievalCandidate>> lexicalFuture;
        if (scope.overflow()) {
            degrade("active_build_scope", degraded);
            degraded.add("ACTIVE_BUILD_FILTER_LIMIT");
            degrade("lexical", degraded);
            lexicalFuture = CompletableFuture.completedFuture(List.of());
        } else {
            lexicalFuture = submitLexical(request, expanded.searchQueries(), scope, recall, degraded, latency);
        }

        List<RetrievalCandidate> semanticCandidates = semanticFuture.join();
        List<RetrievalCandidate> lexicalCandidates = lexicalFuture.join();
        RetrievalTraceContext traceContext = TraceCorrelation.current();
        RetrievalTraceSession traceSession = TraceCorrelation.currentSession();
        traceAction(traceSession, "SEMANTIC_SEARCH", "semantic", Map.of("stage", "semantic", "topK", request.topK()),
                Map.of("candidateCount", semanticCandidates.size()), latency.getOrDefault("semantic", 0L),
                semanticCandidates.size(), degraded.contains("semantic"), degraded);
        traceAction(traceSession, "LEXICAL_SEARCH", "lexical", Map.of("stage", "lexical", "topK", request.topK()),
                Map.of("candidateCount", lexicalCandidates.size()), latency.getOrDefault("lexical", 0L),
                lexicalCandidates.size(), degraded.contains("lexical"), degraded);
        retrievalMetrics.stage(traceContext, "semantic", "semantic",
                degraded.contains("semantic") ? "degraded" : "success", latency.getOrDefault("semantic", 0L));
        retrievalMetrics.stage(traceContext, "lexical", "lexical",
                degraded.contains("lexical") ? "degraded" : "success", latency.getOrDefault("lexical", 0L));
        retrievalMetrics.candidates(traceContext, "semantic", semanticCandidates.size());
        retrievalMetrics.candidates(traceContext, "lexical", lexicalCandidates.size());
        long fusionStarted = System.nanoTime();
        List<RetrievalCandidate> fused = fuse(dedupe(semanticCandidates), dedupe(lexicalCandidates), request.topK());
        latency.put("fusion", elapsed(fusionStarted));
        recordDuration("fusion", latency.get("fusion"));
        record("semantic", semanticCandidates.size());
        record("lexical", lexicalCandidates.size());
        record("fused", fused.size());
        traceAction(traceSession, "HYBRID_FUSION", "hybrid", Map.of("stage", "fusion", "topK", request.topK()),
                Map.of("candidateCount", fused.size()), latency.get("fusion"), fused.size(), false, degraded);
        retrievalMetrics.candidates(traceContext, "hybrid", fused.size());

        boolean rerankApplied = false;
        List<RetrievalCandidate> reranked = List.of();
        List<RetrievalCandidate> ranked = fused;
        boolean rerankEnabled = false;
        try {
            rerankEnabled = reranker.enabled();
        } catch (RuntimeException error) {
            degrade("reranker", degraded);
        }
        if (rerankEnabled && shouldRerank(semanticCandidates, lexicalCandidates, fused, request.query())) {
            long rerankStarted = System.nanoTime();
            try {
                RetrievalCandidateReranker.RerankResult result = CompletableFuture
                        .supplyAsync(() -> TraceCorrelation.call(traceContext, traceSession,
                                () -> candidateReranker.rerank(request.datasetId(), expanded.rerankQuery(), fused)), rerankExecutor)
                        .orTimeout(rerankTimeoutMs, TimeUnit.MILLISECONDS).join();
                reranked = result.candidates();
                ranked = reranked;
                rerankApplied = true;
                if (result.degraded()) degrade("reranker", degraded);
                record("rerank", reranked.size());
            } catch (RuntimeException error) {
                degrade("reranker", degraded);
            } finally {
                long duration = elapsed(rerankStarted);
                latency.put("rerank", duration);
                recordDuration("rerank", duration);
                traceAction(traceSession, "RERANK", "rerank", Map.of("stage", "rerank", "topK", request.topK()),
                        Map.of("candidateCount", reranked.size()), duration, reranked.size(),
                        degraded.contains("reranker"), degraded);
                retrievalMetrics.stage(traceContext, "rerank", "rerank",
                        degraded.contains("reranker") ? "degraded" : "success", duration);
            }
        }
        List<RetrievalCandidate> finalCandidates = select(threshold(ranked, request.threshold(), rerankApplied), request.topK());
        long totalDuration = elapsed(started);
        latency.put("total", totalDuration);
        recordDuration("total", totalDuration);
        traceAction(traceSession, "EVIDENCE_SELECT", "context", Map.of("stage", "context", "topK", request.topK()),
                Map.of("candidateCount", finalCandidates.size()), totalDuration, finalCandidates.size(),
                !degraded.isEmpty(), degraded);
        retrievalMetrics.candidates(traceContext, "context", finalCandidates.size());
        return new RetrievalV2Stages(expanded.rewrittenQuery(), expanded.searchQueries(), expanded.rerankQuery(),
                semanticCandidates, lexicalCandidates, fused, reranked, finalCandidates, rerankApplied,
                degraded.stream().distinct().toList(), latency);
    }

    private void traceAction(RetrievalTraceSession trace, String actionType, String channel,
            Map<String, ?> requestSummary, Map<String, ?> resultSummary, long latencyMs, int candidateCount,
            boolean degraded, Queue<String> degradedComponents) {
        if (trace == null) return;
        trace.action(actionType, channel, requestSummary, resultSummary, latencyMs, candidateCount, degraded,
                degradedComponents == null ? List.of() : degradedComponents.stream().distinct().toList());
    }

    private CompletableFuture<List<RetrievalCandidate>> submitSemantic(RetrievalV2Request request,
            List<String> queries, int recall, Queue<String> degraded, Map<String, Long> latency) {
        final RetrievalTraceContext context = TraceCorrelation.current();
        final RetrievalTraceSession session = TraceCorrelation.currentSession();
        try {
            return CompletableFuture.supplyAsync(() -> TraceCorrelation.call(context, session,
                    () -> timed("semantic", latency, () -> retrieveSemantic(request, queries, recall))), semanticExecutor)
                    .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> failed("semantic", degraded, latency));
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.completedFuture(failed("semantic", degraded, latency));
        }
    }

    private CompletableFuture<List<RetrievalCandidate>> submitLexical(RetrievalV2Request request,
            List<String> queries, ActiveBuildScope scope, int recall, Queue<String> degraded,
            Map<String, Long> latency) {
        final RetrievalTraceContext context = TraceCorrelation.current();
        final RetrievalTraceSession session = TraceCorrelation.currentSession();
        try {
            return CompletableFuture.supplyAsync(() -> TraceCorrelation.call(context, session,
                    () -> timed("lexical", latency, () -> retrieveLexical(request, queries, scope, recall))), lexicalExecutor)
                    .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> failed("lexical", degraded, latency));
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.completedFuture(failed("lexical", degraded, latency));
        }
    }

    private List<RetrievalCandidate> retrieveSemantic(RetrievalV2Request request, List<String> queries, int recall) {
        List<RetrievalCandidate> result = new ArrayList<>();
        for (String query : queries) {
            float[] embedding = embeddings.embed(request.datasetId(), query);
            result.addAll(semantic.search(new SemanticSearchRequest(request.datasetId(), embedding,
                    request.embeddingProfile(), recall)));
        }
        return dedupe(result);
    }

    private List<RetrievalCandidate> retrieveLexical(RetrievalV2Request request, List<String> queries,
            ActiveBuildScope scope, int recall) {
        List<RetrievalCandidate> result = new ArrayList<>();
        for (String query : queries) {
            result.addAll(lexical.search(new LexicalSearchRequest(request.datasetId(), query,
                    scope.indexBuildIds(), recall)));
        }
        return dedupe(result);
    }

    private List<RetrievalCandidate> dedupe(List<RetrievalCandidate> candidates) {
        Map<Long, RetrievalCandidate> unique = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : candidates) {
            if (candidate == null) continue;
            RetrievalCandidate old = unique.get(candidate.retrievalUnitId());
            if (old == null || candidate.score() > old.score()
                    || (candidate.score() == old.score() && candidate.rank() < old.rank())) {
                unique.put(candidate.retrievalUnitId(), candidate);
            }
        }
        List<RetrievalCandidate> sorted = unique.values().stream()
                .sorted(Comparator.comparingDouble(RetrievalCandidate::score).reversed()
                        .thenComparingLong(RetrievalCandidate::retrievalUnitId)).toList();
        return withRanks(sorted, null);
    }

    private List<RetrievalCandidate> fuse(List<RetrievalCandidate> semanticCandidates,
            List<RetrievalCandidate> lexicalCandidates, int topK) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, RetrievalCandidate> representatives = new HashMap<>();
        addRrf(scores, representatives, semanticCandidates, semanticWeight);
        addRrf(scores, representatives, lexicalCandidates, lexicalWeight);
        int limit = Math.min(MAX_RECALL, Math.max(20, topK * 4));
        List<Long> ids = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit).map(Map.Entry::getKey).toList();
        List<RetrievalCandidate> result = new ArrayList<>();
        for (Long id : ids) {
            RetrievalCandidate value = representatives.get(id);
            result.add(new RetrievalCandidate(value.datasetId(), value.retrievalUnitId(), value.nodeId(),
                    value.documentId(), value.documentVersionId(), value.indexBuildId(), value.unitType(),
                    value.titlePath(), value.content(), scores.get(id), RetrievalChannel.FUSED,
                    result.size() + 1, value.metadata()));
        }
        return List.copyOf(result);
    }

    private void addRrf(Map<Long, Double> scores, Map<Long, RetrievalCandidate> representatives,
            List<RetrievalCandidate> candidates, double weight) {
        for (int index = 0; index < candidates.size(); index++) {
            RetrievalCandidate candidate = candidates.get(index);
            scores.merge(candidate.retrievalUnitId(), weight / (RRF_K + index + 1), Double::sum);
            representatives.putIfAbsent(candidate.retrievalUnitId(), candidate);
        }
    }

    private boolean shouldRerank(List<RetrievalCandidate> semanticCandidates,
            List<RetrievalCandidate> lexicalCandidates, List<RetrievalCandidate> fused, String query) {
        if (fused.size() >= 8) return true;
        if (query != null && (query.contains("？") || query.contains("?") || query.contains("是否"))) return true;
        if (fused.size() < 2) return false;
        double first = fused.get(0).score();
        double second = fused.get(1).score();
        boolean smallGap = first <= 0 || (first - second) / first < .10;
        Set<Long> lexicalIds = lexicalCandidates.stream().map(RetrievalCandidate::retrievalUnitId).collect(java.util.stream.Collectors.toSet());
        long overlap = semanticCandidates.stream().map(RetrievalCandidate::retrievalUnitId)
                .filter(lexicalIds::contains).distinct().count();
        double overlapRatio = Math.min(semanticCandidates.size(), lexicalCandidates.size()) == 0
                ? 0 : (double) overlap / Math.min(semanticCandidates.size(), lexicalCandidates.size());
        return smallGap || overlapRatio < .40;
    }

    private List<RetrievalCandidate> threshold(List<RetrievalCandidate> candidates, double threshold,
            boolean rerankApplied) {
        if (threshold <= 0) return candidates;
        if (rerankApplied) return candidates.stream().filter(item -> item.score() >= threshold).toList();
        double max = candidates.stream().mapToDouble(RetrievalCandidate::score).max().orElse(0);
        if (max <= 0) return List.of();
        return candidates.stream().filter(item -> item.score() / max >= threshold).toList();
    }

    private List<RetrievalCandidate> select(List<RetrievalCandidate> candidates, int topK) {
        return withRanks(candidates.stream().limit(topK).toList(), null);
    }

    private List<RetrievalCandidate> withRanks(List<RetrievalCandidate> candidates, RetrievalChannel channel) {
        if (candidates.isEmpty()) return List.of();
        List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
        for (RetrievalCandidate candidate : candidates) {
            result.add(new RetrievalCandidate(candidate.datasetId(), candidate.retrievalUnitId(), candidate.nodeId(),
                    candidate.documentId(), candidate.documentVersionId(), candidate.indexBuildId(), candidate.unitType(),
                    candidate.titlePath(), candidate.content(), candidate.score(),
                    channel == null ? candidate.channel() : channel, result.size() + 1, candidate.metadata()));
        }
        return List.copyOf(result);
    }

    private <T> T timed(String stage, Map<String, Long> latency, Supplier<T> action) {
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            long duration = elapsed(started);
            latency.put(stage, duration);
            recordDuration(stage, duration);
        }
    }

    private List<RetrievalCandidate> failed(String component, Queue<String> degraded, Map<String, Long> latency) {
        degrade(component, degraded);
        latency.putIfAbsent(component, channelTimeoutMs);
        return List.of();
    }

    private void degrade(String component, Queue<String> degraded) {
        degraded.add(component);
        metrics.counter("modelrag.retrieval.v2.degraded", "stage", component).increment();
    }

    private void record(String channel, int count) {
        metrics.counter("modelrag.retrieval.v2.candidates", "channel", channel).increment(count);
    }

    private void recordDuration(String stage, long durationMs) {
        metrics.timer("modelrag.retrieval.v2.duration", "stage", stage)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @FunctionalInterface
    private interface Supplier<T> { T get(); }
}
