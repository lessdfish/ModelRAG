package com.modelrag.search.orchestrator;

import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.api.DiagnosticTextEmbeddingProvider;
import com.modelrag.common.observability.RetrievalMetrics;
import com.modelrag.common.observability.RetrievalTraceContext;
import com.modelrag.common.observability.RetrievalTraceSession;
import com.modelrag.common.observability.RetrievalTraceSink;
import com.modelrag.common.observability.TraceCorrelation;
import com.modelrag.search.channel.v2.ActiveBuildScope;
import com.modelrag.search.channel.v2.ActiveBuildScopeResolver;
import com.modelrag.search.channel.v2.LexicalSearchPort;
import com.modelrag.search.channel.v2.LexicalSearchRequest;
import com.modelrag.search.channel.v2.MeasuredLexicalSearchPort;
import com.modelrag.search.channel.v2.MeasuredSemanticSearchPort;
import com.modelrag.search.channel.v2.SemanticSearchPort;
import com.modelrag.search.channel.v2.SemanticSearchRequest;
import com.modelrag.search.channel.v2.SemanticSearchTimeoutException;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import com.modelrag.search.dto.RetrievalV2Request;
import com.modelrag.search.dto.RetrievalV2Stages;
import com.modelrag.search.reranker.Reranker;
import com.modelrag.search.rewrite.QueryRewriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final long SEMANTIC_DB_SAFETY_MARGIN_MS = 25;

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
    private final long semanticMinExecutionBudgetMs;
    private final long lexicalMinExecutionBudgetMs;
    private final long rerankTimeoutMs;
    private final MeterRegistry metrics;
    private final Executor semanticExecutor;
    private final Executor lexicalExecutor;
    private final Executor rerankExecutor;
    private final RetrievalMetrics retrievalMetrics;
    private volatile RetrievalTraceSink traceSink = RetrievalTraceSink.NOOP;

    public enum Mode { SEMANTIC_ONLY, LEXICAL_ONLY, HYBRID }

    @Autowired
    public HybridRetrievalService(SemanticSearchPort semantic, LexicalSearchPort lexical,
            TextEmbeddingProvider embeddings, QueryRewriter rewriter, Reranker reranker,
            ActiveBuildScopeResolver activeBuilds,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.rrf-vector-weight:.7}") double semanticWeight,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.rrf-bm25-weight:.3}") double lexicalWeight,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.channel-timeout-ms:800}") long channelTimeoutMs,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.semantic-min-execution-budget-ms:300}") long semanticMinExecutionBudgetMs,
            @org.springframework.beans.factory.annotation.Value("${modelrag.search.lexical-min-execution-budget-ms:125}") long lexicalMinExecutionBudgetMs,
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
        this.semanticMinExecutionBudgetMs = boundedMinimumBudget(semanticMinExecutionBudgetMs, this.channelTimeoutMs);
        this.lexicalMinExecutionBudgetMs = boundedMinimumBudget(lexicalMinExecutionBudgetMs, this.channelTimeoutMs);
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
        return inspect(request, Mode.HYBRID);
    }

    public RetrievalV2Stages inspect(RetrievalV2Request request, Mode mode) {
        RetrievalTraceContext context = TraceCorrelation.current();
        if (context == null) context = RetrievalTraceContext.create("V2", request.datasetId(), null,
                request.embeddingProfile());
        RetrievalTraceSession session = TraceCorrelation.currentSession();
        boolean ownsSession = session == null;
        if (ownsSession) session = RetrievalTraceSession.start(traceSink, context, "redacted");
        try (TraceCorrelation.Scope ignored = TraceCorrelation.bind(context, session)) {
            RetrievalV2Stages result = inspectInternal(request, mode == null ? Mode.HYBRID : mode);
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

    private RetrievalV2Stages inspectInternal(RetrievalV2Request request, Mode mode) {
        long started = System.nanoTime();
        var expanded = rewriter.expand(request.query());
        Queue<String> degraded = new ConcurrentLinkedQueue<>();
        Map<String, Long> latency = new ConcurrentHashMap<>();
        Map<String, Double> performance = new ConcurrentHashMap<>();
        Map<String, String> performanceAttributes = new ConcurrentHashMap<>();
        int recall = Math.min(MAX_RECALL, Math.max(request.topK() * 2, 10));

        CompletableFuture<List<RetrievalCandidate>> semanticFuture = mode == Mode.LEXICAL_ONLY
                ? CompletableFuture.completedFuture(List.of())
                : submitSemantic(request, expanded.semanticQuery(), recall, degraded, latency,
                        performance, performanceAttributes);
        ActiveBuildScope scope = new ActiveBuildScope(List.of(), false);
        if (mode != Mode.SEMANTIC_ONLY) {
            try {
                scope = activeBuilds.resolve(request.datasetId());
            } catch (RuntimeException error) {
                scope = new ActiveBuildScope(List.of(), true);
            }
        }
        CompletableFuture<List<RetrievalCandidate>> lexicalFuture;
        lexicalFuture = mode == Mode.SEMANTIC_ONLY ? CompletableFuture.completedFuture(List.of())
                : submitLexical(request, expanded.lexicalQueries(), scope, recall, degraded, latency,
                        performance, performanceAttributes);

        List<RetrievalCandidate> semanticCandidates = semanticFuture.join();
        List<RetrievalCandidate> lexicalCandidates = lexicalFuture.join();
        RetrievalTraceContext traceContext = TraceCorrelation.current();
        RetrievalTraceSession traceSession = TraceCorrelation.currentSession();
        traceAction(traceSession, "SEMANTIC_SEARCH", "semantic", Map.of("stage", "semantic", "topK", request.topK()),
                Map.of("candidateCount", semanticCandidates.size()), latency.getOrDefault("semantic", 0L),
                semanticCandidates.size(), degradedFor(degraded, "SEMANTIC_"), degraded);
        traceAction(traceSession, "LEXICAL_SEARCH", "lexical", Map.of("stage", "lexical", "topK", request.topK()),
                Map.of("candidateCount", lexicalCandidates.size()), latency.getOrDefault("lexical", 0L),
                lexicalCandidates.size(), degradedFor(degraded, "LEXICAL_"), degraded);
        retrievalMetrics.stage(traceContext, "semantic", "semantic",
                degradedFor(degraded, "SEMANTIC_") ? "degraded" : "success", latency.getOrDefault("semantic", 0L));
        retrievalMetrics.stage(traceContext, "lexical", "lexical",
                degradedFor(degraded, "LEXICAL_") ? "degraded" : "success", latency.getOrDefault("lexical", 0L));
        retrievalMetrics.candidates(traceContext, "semantic", semanticCandidates.size());
        retrievalMetrics.candidates(traceContext, "lexical", lexicalCandidates.size());
        long fusionStarted = System.nanoTime();
        List<RetrievalCandidate> fused = switch (mode) {
            case SEMANTIC_ONLY -> dedupe(semanticCandidates);
            case LEXICAL_ONLY -> dedupe(lexicalCandidates);
            case HYBRID -> fuse(dedupe(semanticCandidates), dedupe(lexicalCandidates), request.topK());
        };
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
            degrade("RERANK_ERROR", degraded);
        }
        if (mode == Mode.HYBRID && rerankEnabled
                && shouldRerank(semanticCandidates, lexicalCandidates, fused, request.query())) {
            long rerankStarted = System.nanoTime();
            long rerankSubmittedAt = System.nanoTime();
            try {
                RetrievalCandidateReranker.RerankResult result = CompletableFuture
                        .supplyAsync(() -> {
                            recordPerformance("rerank.queueWaitMs", System.nanoTime() - rerankSubmittedAt,
                                    performance);
                            return TraceCorrelation.call(traceContext, traceSession,
                                    () -> candidateReranker.rerank(request.datasetId(),
                                            expanded.rerankQuery(), fused));
                        }, rerankExecutor)
                        .orTimeout(rerankTimeoutMs, TimeUnit.MILLISECONDS).join();
                reranked = result.candidates();
                ranked = reranked;
                rerankApplied = true;
                if (result.degraded()) degrade("RERANK_ERROR", degraded);
                record("rerank", reranked.size());
            } catch (RuntimeException error) {
                degrade(failureComponent("RERANK", error), degraded);
            } finally {
                long duration = elapsed(rerankStarted);
                latency.put("rerank", duration);
                recordDuration("rerank", duration);
                traceAction(traceSession, "RERANK", "rerank", Map.of("stage", "rerank", "topK", request.topK()),
                        Map.of("candidateCount", reranked.size()), duration, reranked.size(),
                        degradedFor(degraded, "RERANK_"), degraded);
                retrievalMetrics.stage(traceContext, "rerank", "rerank",
                        degradedFor(degraded, "RERANK_") ? "degraded" : "success", duration);
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
        return new RetrievalV2Stages(expanded.rewrittenQuery(), expanded.lexicalQueries(), expanded.rerankQuery(),
                semanticCandidates, lexicalCandidates, fused, reranked, finalCandidates, rerankApplied,
                degraded.stream().distinct().toList(), latency, performance, performanceAttributes);
    }

    private void traceAction(RetrievalTraceSession trace, String actionType, String channel,
            Map<String, ?> requestSummary, Map<String, ?> resultSummary, long latencyMs, int candidateCount,
            boolean degraded, Queue<String> degradedComponents) {
        if (trace == null) return;
        trace.action(actionType, channel, requestSummary, resultSummary, latencyMs, candidateCount, degraded,
                degradedComponents == null ? List.of() : degradedComponents.stream().distinct().toList());
    }

    private CompletableFuture<List<RetrievalCandidate>> submitSemantic(RetrievalV2Request request,
            String query, int recall, Queue<String> degraded, Map<String, Long> latency,
            Map<String, Double> performance, Map<String, String> attributes) {
        final RetrievalTraceContext context = TraceCorrelation.current();
        final RetrievalTraceSession session = TraceCorrelation.currentSession();
        final long submittedAt = System.nanoTime();
        final long deadline = submittedAt + TimeUnit.MILLISECONDS.toNanos(channelTimeoutMs);
        final AtomicBoolean started = new AtomicBoolean();
        try {
            return CompletableFuture.supplyAsync(() -> TraceCorrelation.call(context, session,
                    () -> {
                        started.set(true);
                        long taskStarted = System.nanoTime();
                        long queueWaitNanos = taskStarted - submittedAt;
                        recordPerformance("semantic.queueWaitMs", queueWaitNanos, performance);
                        try {
                            if (!admit("SEMANTIC", deadline, semanticMinExecutionBudgetMs, degraded, attributes)) {
                                latency.putIfAbsent("semantic", 0L);
                                return List.<RetrievalCandidate>of();
                            }
                            long executionStarted = System.nanoTime();
                            try {
                                return timed("semantic", latency,
                                        () -> retrieveSemantic(request, query, recall, deadline, degraded,
                                                performance, attributes));
                            } finally {
                                recordPerformance("semantic.executionMs", System.nanoTime() - executionStarted,
                                        performance);
                            }
                        } finally {
                            recordPerformance("semantic.channelMs", System.nanoTime() - submittedAt, performance);
                        }
                    }), semanticExecutor)
                    .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        if (hasSqlState(error, "57014")) {
                            metrics.counter("modelrag.retrieval.v2.semantic.db.statement-timeout").increment();
                            attributes.put("semantic.dbStatementTimeout", "true");
                        }
                        if (isTimeout(error) && !started.get()) {
                            attributes.put("semantic.queueStatus", "TIMED_OUT_BEFORE_START");
                            return admissionFailed("SEMANTIC", degraded, latency);
                        }
                        return failed("SEMANTIC", error, degraded, latency);
                    })
                    .whenComplete((ignored, error) -> recordPerformance("semantic.requestObservedMs",
                            System.nanoTime() - submittedAt, performance));
        } catch (RejectedExecutionException rejected) {
            List<RetrievalCandidate> result = rejected("SEMANTIC", degraded, latency, attributes);
            recordPerformance("semantic.requestObservedMs", System.nanoTime() - submittedAt, performance);
            return CompletableFuture.completedFuture(result);
        }
    }

    private CompletableFuture<List<RetrievalCandidate>> submitLexical(RetrievalV2Request request,
            List<String> queries, ActiveBuildScope scope, int recall, Queue<String> degraded,
            Map<String, Long> latency, Map<String, Double> performance, Map<String, String> attributes) {
        final RetrievalTraceContext context = TraceCorrelation.current();
        final RetrievalTraceSession session = TraceCorrelation.currentSession();
        final long submittedAt = System.nanoTime();
        final long deadline = submittedAt + TimeUnit.MILLISECONDS.toNanos(channelTimeoutMs);
        final AtomicBoolean started = new AtomicBoolean();
        try {
            return CompletableFuture.supplyAsync(() -> TraceCorrelation.call(context, session,
                    () -> {
                        started.set(true);
                        long taskStarted = System.nanoTime();
                        recordPerformance("lexical.queueWaitMs", taskStarted - submittedAt, performance);
                        try {
                            if (!admit("LEXICAL", deadline, lexicalMinExecutionBudgetMs, degraded, attributes)) {
                                latency.putIfAbsent("lexical", 0L);
                                return List.<RetrievalCandidate>of();
                            }
                            long executionStarted = System.nanoTime();
                            try {
                                return timed("lexical", latency,
                                        () -> retrieveLexical(request, queries, scope, recall, degraded, performance));
                            } finally {
                                recordPerformance("lexical.executionMs", System.nanoTime() - executionStarted,
                                        performance);
                            }
                        } finally {
                            recordPerformance("lexical.channelMs", System.nanoTime() - submittedAt, performance);
                        }
                    }), lexicalExecutor)
                    .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        if (isTimeout(error) && !started.get()) {
                            attributes.put("lexical.queueStatus", "TIMED_OUT_BEFORE_START");
                            return admissionFailed("LEXICAL", degraded, latency);
                        }
                        return failed("LEXICAL", error, degraded, latency);
                    })
                    .whenComplete((ignored, error) -> recordPerformance("lexical.requestObservedMs",
                            System.nanoTime() - submittedAt, performance));
        } catch (RejectedExecutionException rejected) {
            List<RetrievalCandidate> result = rejected("LEXICAL", degraded, latency, attributes);
            recordPerformance("lexical.requestObservedMs", System.nanoTime() - submittedAt, performance);
            return CompletableFuture.completedFuture(result);
        }
    }

    private List<RetrievalCandidate> retrieveSemantic(RetrievalV2Request request, String query, int recall,
            long deadline, Queue<String> degraded, Map<String, Double> performance,
            Map<String, String> attributes) {
        long embeddingStarted = System.nanoTime();
        float[] embedding;
        if (embeddings instanceof DiagnosticTextEmbeddingProvider diagnostic) {
            DiagnosticTextEmbeddingProvider.EmbeddingInvocation invocation =
                    diagnostic.embedWithDiagnostics(request.datasetId(), query);
            if (invocation == null) {
                embedding = embeddings.embed(request.datasetId(), query);
                attributes.put("semantic.embeddingCache", "NOT_PRESENT");
            } else {
                embedding = invocation.vector();
                performance.put("semantic.embeddingCacheLookupMs", invocation.cacheLookupMs());
                recordPerformanceMs("semantic.embeddingCacheLookupMs", invocation.cacheLookupMs());
                performance.put("semantic.embeddingRemoteMs", invocation.remoteCallMs());
                attributes.put("semantic.embeddingCache", "PRESENT");
                attributes.put("semantic.embeddingCacheOutcome", invocation.cacheOutcome());
                if (invocation.remoteCallMs() > 0) {
                    recordPerformanceMs("semantic.embeddingRemoteMs", invocation.remoteCallMs());
                }
                metrics.counter("modelrag.retrieval.v2.embedding.cache",
                        "outcome", invocation.cacheOutcome()).increment();
            }
        } else {
            embedding = embeddings.embed(request.datasetId(), query);
            attributes.put("semantic.embeddingCache", "NOT_PRESENT");
        }
        recordPerformance("semantic.embeddingMs", System.nanoTime() - embeddingStarted, performance);
        long remainingNanos = deadline - System.nanoTime()
                - TimeUnit.MILLISECONDS.toNanos(SEMANTIC_DB_SAFETY_MARGIN_MS);
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (remainingMs < 1) {
            throw new SemanticSearchTimeoutException("semantic deadline exhausted before vector search");
        }
        attributes.put("semantic.dbTimeoutMs", Long.toString(remainingMs));
        long vectorStarted = System.nanoTime();
        try {
            SemanticSearchRequest semanticRequest = new SemanticSearchRequest(request.datasetId(), embedding,
                    request.embeddingProfile(), recall, remainingMs);
            if (semantic instanceof MeasuredSemanticSearchPort measured) {
                MeasuredSemanticSearchPort.MeasuredResult result = measured.searchMeasured(semanticRequest);
                attributes.put("semantic.annRefillRounds", Integer.toString(result.refillRounds()));
                attributes.put("semantic.annCandidateBudget", Integer.toString(result.candidateBudget()));
                attributes.put("semantic.annRefillExhausted", Boolean.toString(result.refillExhausted()));
                metrics.counter("modelrag.retrieval.v2.semantic.ann.requests").increment();
                if (result.refillRounds() > 0) {
                    metrics.counter("modelrag.retrieval.v2.semantic.ann.refilled").increment();
                    metrics.counter("modelrag.retrieval.v2.semantic.ann.refill-rounds")
                            .increment(result.refillRounds());
                }
                if (result.refillExhausted()) {
                    metrics.counter("modelrag.retrieval.v2.semantic.ann.refill-exhausted").increment();
                    degrade("SEMANTIC_ANN_REFILL_EXHAUSTED", degraded);
                }
                return dedupe(result.candidates());
            }
            return dedupe(semantic.search(semanticRequest));
        } finally {
            recordPerformance("semantic.vectorSearchMs", System.nanoTime() - vectorStarted, performance);
        }
    }

    private List<RetrievalCandidate> retrieveLexical(RetrievalV2Request request, List<String> queries,
            ActiveBuildScope scope, int recall, Queue<String> degraded, Map<String, Double> performance) {
        List<List<RetrievalCandidate>> perQuery = new ArrayList<>();
        long elasticsearchNanos = 0;
        long activeValidationNanos = 0;
        for (String query : queries) {
            LexicalSearchRequest lexicalRequest = new LexicalSearchRequest(request.datasetId(), query,
                    scope.indexBuildIds(), recall);
            if (lexical instanceof MeasuredLexicalSearchPort measured) {
                MeasuredLexicalSearchPort.MeasuredResult measuredResult =
                        measured.searchMeasured(lexicalRequest, scope.overflow());
                LexicalSearchPort.ActiveValidatedResult page = measuredResult.result();
                perQuery.add(page.candidates());
                if (page.truncated()) degraded.add("ACTIVE_BUILD_VALIDATION_TRUNCATED");
                elasticsearchNanos += measuredResult.elasticsearchNanos();
                activeValidationNanos += measuredResult.activeValidationNanos();
            } else {
                long fallbackStarted = System.nanoTime();
                if (scope.overflow()) {
                    LexicalSearchPort.ActiveValidatedResult page = lexical.searchActiveValidatedResult(lexicalRequest);
                    perQuery.add(page.candidates());
                    if (page.truncated()) degraded.add("ACTIVE_BUILD_VALIDATION_TRUNCATED");
                } else {
                    perQuery.add(lexical.search(lexicalRequest));
                }
                elasticsearchNanos += System.nanoTime() - fallbackStarted;
            }
        }
        recordPerformance("lexical.elasticsearchMs", elasticsearchNanos, performance);
        recordPerformance("lexical.activeValidationMs", activeValidationNanos, performance);
        long fusionStarted = System.nanoTime();
        try {
            return fuseLexicalQueries(perQuery, recall);
        } finally {
            recordPerformance("lexical.internalFusionMs", System.nanoTime() - fusionStarted, performance);
        }
    }

    private List<RetrievalCandidate> fuseLexicalQueries(List<List<RetrievalCandidate>> perQuery, int limit) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, RetrievalCandidate> representatives = new HashMap<>();
        for (List<RetrievalCandidate> queryCandidates : perQuery) {
            List<RetrievalCandidate> ranked = queryCandidates.stream()
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparingInt(RetrievalCandidate::rank)
                            .thenComparingLong(RetrievalCandidate::retrievalUnitId))
                    .toList();
            for (int index = 0; index < ranked.size(); index++) {
                RetrievalCandidate candidate = ranked.get(index);
                scores.merge(candidate.retrievalUnitId(), 1d / (RRF_K + index + 1), Double::sum);
                representatives.putIfAbsent(candidate.retrievalUnitId(), candidate);
            }
        }
        List<Long> ids = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(limit).map(Map.Entry::getKey).toList();
        List<RetrievalCandidate> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            RetrievalCandidate candidate = representatives.get(id);
            result.add(new RetrievalCandidate(candidate.datasetId(), candidate.retrievalUnitId(), candidate.nodeId(),
                    candidate.documentId(), candidate.documentVersionId(), candidate.indexBuildId(),
                    candidate.unitType(), candidate.titlePath(), candidate.content(), scores.get(id),
                    RetrievalChannel.LEXICAL, result.size() + 1, candidate.metadata()));
        }
        return List.copyOf(result);
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

    private boolean admit(String component, long deadline, long minimumBudgetMs,
            Queue<String> degraded, Map<String, String> attributes) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, deadline - System.nanoTime()));
        String prefix = component.toLowerCase(java.util.Locale.ROOT);
        attributes.put(prefix + ".remainingAtAdmissionMs", Long.toString(remainingMs));
        attributes.put(prefix + ".minimumExecutionBudgetMs", Long.toString(minimumBudgetMs));
        if (remainingMs >= minimumBudgetMs) return true;
        attributes.put(prefix + ".queueStatus", "ADMISSION_TIMEOUT");
        degradeOnce(component + "_ADMISSION_TIMEOUT", degraded);
        return false;
    }

    private List<RetrievalCandidate> admissionFailed(String component,
            Queue<String> degraded, Map<String, Long> latency) {
        degradeOnce(component + "_ADMISSION_TIMEOUT", degraded);
        latency.putIfAbsent(component.toLowerCase(java.util.Locale.ROOT), channelTimeoutMs);
        return List.of();
    }

    private List<RetrievalCandidate> rejected(String component, Queue<String> degraded,
            Map<String, Long> latency, Map<String, String> attributes) {
        String prefix = component.toLowerCase(java.util.Locale.ROOT);
        attributes.put(prefix + ".queueStatus", "REJECTED");
        degradeOnce(component + "_REJECTED", degraded);
        latency.putIfAbsent(prefix, 0L);
        return List.of();
    }

    private List<RetrievalCandidate> failed(String component, Throwable error,
            Queue<String> degraded, Map<String, Long> latency) {
        String failure = failureComponent(component, error);
        degrade(failure, degraded);
        latency.putIfAbsent(component.toLowerCase(java.util.Locale.ROOT),
                failure.endsWith("_TIMEOUT") ? channelTimeoutMs : 0L);
        return List.of();
    }

    private String failureComponent(String component, Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof RejectedExecutionException) return component + "_REJECTED";
        return component + (cause instanceof TimeoutException || cause instanceof SemanticSearchTimeoutException
                || hasSqlState(cause, "57014") ? "_TIMEOUT" : "_ERROR");
    }

    private boolean isTimeout(Throwable error) {
        return failureComponent("CHANNEL", error).endsWith("_TIMEOUT");
    }

    private boolean hasSqlState(Throwable error, String sqlState) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof java.sql.SQLException sql && sqlState.equals(sql.getSQLState())) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private boolean degradedFor(Queue<String> degraded, String prefix) {
        return degraded.stream().anyMatch(value -> value.startsWith(prefix));
    }

    private void degrade(String component, Queue<String> degraded) {
        degraded.add(component);
        metrics.counter("modelrag.retrieval.v2.degraded", "stage", component).increment();
    }

    private void degradeOnce(String component, Queue<String> degraded) {
        if (degraded.contains(component)) return;
        degrade(component, degraded);
    }

    private void record(String channel, int count) {
        metrics.counter("modelrag.retrieval.v2.candidates", "channel", channel).increment(count);
    }

    private void recordDuration(String stage, long durationMs) {
        metrics.timer("modelrag.retrieval.v2.duration", "stage", stage)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    private void recordPerformance(String stage, long durationNanos, Map<String, Double> values) {
        double durationMs = durationNanos / 1_000_000d;
        if (values != null) values.put(stage, durationMs);
        Timer.builder("modelrag.retrieval.v2.performance").tag("stage", stage)
                .publishPercentiles(.5, .95, .99).register(metrics)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private void recordPerformanceMs(String stage, double durationMs) {
        Timer.builder("modelrag.retrieval.v2.performance").tag("stage", stage)
                .publishPercentiles(.5, .95, .99).register(metrics)
                .record((long) (durationMs * 1_000_000d), TimeUnit.NANOSECONDS);
    }

    private long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static long boundedMinimumBudget(long value, long timeoutMs) {
        return Math.max(1, Math.min(value, Math.max(1, timeoutMs - 1)));
    }

    @FunctionalInterface
    private interface Supplier<T> { T get(); }
}
