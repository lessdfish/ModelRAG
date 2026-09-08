package com.modelrag.search.orchestrator;

import com.modelrag.common.vector.SearchRequest;
import com.modelrag.common.vector.SearchResult;
import com.modelrag.common.vector.VectorStore;
import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.knowledge.repository.IndexVersionRepository;
import com.modelrag.search.channel.Bm25Search;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import com.modelrag.search.reranker.Reranker;
import com.modelrag.search.rewrite.QueryRewriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Parallel hybrid retrieval with an explicit deadline and observable degraded paths. */
@Service
public class SearchOrchestrator implements SearchFacade {
    private final VectorStore vectors;
    private final EmbeddingService embeddings;
    private final Bm25Search bm25;
    private final Reranker reranker;
    private final QueryRewriter rewriter;
    private final IndexVersionRepository versions;
    private final double vectorWeight;
    private final double bm25Weight;
    private final MeterRegistry metrics;
    private final Executor vectorExecutor;
    private final Executor bm25Executor;
    private final Executor rerankExecutor;
    private final Executor shadowExecutor;
    private final ObjectProvider<com.modelrag.search.shadow.RetrievalShadowComparator> shadowComparator;
    private final boolean shadowEnabled;
    private final long channelTimeoutMs;
    private final long rerankTimeoutMs;

    public SearchOrchestrator(VectorStore vectors, EmbeddingService embeddings, Bm25Search bm25,
            Reranker reranker, QueryRewriter rewriter,
            IndexVersionRepository versions,
            @Value("${modelrag.search.rrf-vector-weight:.7}") double vectorWeight,
            @Value("${modelrag.search.rrf-bm25-weight:.3}") double bm25Weight,
            @Value("${modelrag.search.channel-timeout-ms:800}") long channelTimeoutMs,
            @Value("${modelrag.search.rerank-timeout-ms:500}") long rerankTimeoutMs,
            MeterRegistry metrics,
            @Qualifier("vectorSearchExecutor") Executor vectorExecutor,
            @Qualifier("bm25SearchExecutor") Executor bm25Executor,
            @Qualifier("rerankExecutor") Executor rerankExecutor) {
        this.vectors = vectors;
        this.embeddings = embeddings;
        this.bm25 = bm25;
        this.reranker = reranker;
        this.rewriter = rewriter;
        this.versions = versions;
        this.vectorWeight = vectorWeight;
        this.bm25Weight = bm25Weight;
        this.channelTimeoutMs = Math.max(50, channelTimeoutMs);
        this.rerankTimeoutMs = Math.max(50, rerankTimeoutMs);
        this.metrics = metrics;
        this.vectorExecutor = vectorExecutor;
        this.bm25Executor = bm25Executor;
        this.rerankExecutor = rerankExecutor;
        this.shadowExecutor = Runnable::run;
        this.shadowComparator = null;
        this.shadowEnabled = false;
    }

    @Autowired
    public SearchOrchestrator(VectorStore vectors, EmbeddingService embeddings, Bm25Search bm25,
            Reranker reranker, QueryRewriter rewriter,
            IndexVersionRepository versions,
            @Value("${modelrag.search.rrf-vector-weight:.7}") double vectorWeight,
            @Value("${modelrag.search.rrf-bm25-weight:.3}") double bm25Weight,
            @Value("${modelrag.search.channel-timeout-ms:800}") long channelTimeoutMs,
            @Value("${modelrag.search.rerank-timeout-ms:500}") long rerankTimeoutMs,
            MeterRegistry metrics,
            @Qualifier("vectorSearchExecutor") Executor vectorExecutor,
            @Qualifier("bm25SearchExecutor") Executor bm25Executor,
            @Qualifier("rerankExecutor") Executor rerankExecutor,
            @Qualifier("retrievalShadowExecutor") Executor shadowExecutor,
            ObjectProvider<com.modelrag.search.shadow.RetrievalShadowComparator> shadowComparator,
            @Value("${modelrag.retrieval.v2.shadow-enabled:false}") boolean shadowEnabled) {
        this.vectors = vectors;
        this.embeddings = embeddings;
        this.bm25 = bm25;
        this.reranker = reranker;
        this.rewriter = rewriter;
        this.versions = versions;
        this.vectorWeight = vectorWeight;
        this.bm25Weight = bm25Weight;
        this.channelTimeoutMs = Math.max(50, channelTimeoutMs);
        this.rerankTimeoutMs = Math.max(50, rerankTimeoutMs);
        this.metrics = metrics;
        this.vectorExecutor = vectorExecutor;
        this.bm25Executor = bm25Executor;
        this.rerankExecutor = rerankExecutor;
        this.shadowExecutor = shadowExecutor;
        this.shadowComparator = shadowComparator;
        this.shadowEnabled = shadowEnabled;
    }

    @Override
    public SearchStages inspect(HybridSearchRequest request) {
        final HybridSearchRequest scopedRequest = request.withActiveIndexVersions(
                versions.findActiveByDatasetId(request.datasetId()));
        long started = System.nanoTime();
        int topK = Math.max(1, scopedRequest.topK());
        int recall = Math.max(topK * 2, 10);
        var expanded = rewriter.expand(request.query());
        Queue<String> degraded = new ConcurrentLinkedQueue<>();
        Map<String, Long> latency = new ConcurrentHashMap<>();

        CompletableFuture<ChannelResult> vectorFuture = CompletableFuture
                .supplyAsync(() -> timed("pgvector", () -> retrieveVector(scopedRequest.datasetId(), expanded.searchQueries(), recall), latency), vectorExecutor)
                .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(error -> failed("pgvector", error, degraded, latency));
        CompletableFuture<ChannelResult> bm25Future = CompletableFuture
                .supplyAsync(() -> timed("elasticsearch", () -> retrieveBm25(scopedRequest, expanded.searchQueries(), topK, recall), latency), bm25Executor)
                .orTimeout(channelTimeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(error -> failed("elasticsearch", error, degraded, latency));

        List<ScoredChunk> vector = vectorFuture.join().results();
        List<ScoredChunk> lexical = bm25Future.join().results();
        Map<Long, Double> fusedScores = new HashMap<>();
        Map<Long, String> text = new HashMap<>();
        add(fusedScores, text, vector, vectorWeight);
        add(fusedScores, text, lexical, bm25Weight);
        List<ScoredChunk> fused = rank(fusedScores.entrySet().stream()
                .map(entry -> new ScoredChunk(entry.getKey(), text.get(entry.getKey()), entry.getValue(), "rrf", 0))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(20)
                .toList());

        boolean shouldRerank = reranker.enabled() && shouldRerank(vector, lexical, fused, scopedRequest.query());
        List<ScoredChunk> reranked = fused;
        boolean rerankApplied = false;
        if (shouldRerank) {
            long rerankStarted = System.nanoTime();
            try {
                reranked = CompletableFuture.supplyAsync(() -> reranker.rerank(scopedRequest.datasetId(), expanded.rerankQuery(), fused), rerankExecutor)
                        .orTimeout(rerankTimeoutMs, TimeUnit.MILLISECONDS)
                        .join();
                rerankApplied = true;
                if (reranked.stream().anyMatch(item -> "local-rerank".equals(item.channel()))) {
                    degraded.add("reranker");
                    metrics.counter("modelrag.retrieval.degraded", "component", "reranker").increment();
                }
            } catch (RuntimeException error) {
                degraded.add("reranker");
                metrics.counter("modelrag.retrieval.degraded", "component", "reranker").increment();
            } finally {
                latency.put("reranker", (System.nanoTime() - rerankStarted) / 1_000_000);
            }
        }
        List<ScoredChunk> thresholded = threshold(reranked, scopedRequest.threshold(), rerankApplied);
        List<ScoredChunk> finalResults = rank(selectContext(thresholded, topK, rerankApplied));
        record("vector", vector.size());
        record("bm25", lexical.size());
        record("fused", fused.size());
        record(rerankApplied ? "rerank" : "rrf", reranked.size());
        record("threshold", thresholded.size());
        record("context", finalResults.size());
        latency.putIfAbsent("total", (System.nanoTime() - started) / 1_000_000);
        submitShadow(request, finalResults, latency.getOrDefault("total", 0L));
        return new SearchStages(expanded.rewrittenQuery(), expanded.searchQueries(), expanded.rerankQuery(),
                vector, lexical, fused, rerankApplied ? reranked : List.of(), rerankApplied, finalResults,
                degraded.stream().distinct().toList(), latency);
    }

    private ChannelResult timed(String component, ChannelSupplier supplier, Map<String, Long> latency) {
        long started = System.nanoTime();
        try {
            return new ChannelResult(supplier.get());
        } finally {
            latency.put(component, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private ChannelResult failed(String component, Throwable error, Queue<String> degraded, Map<String, Long> latency) {
        degraded.add(component);
        metrics.counter("modelrag.retrieval.degraded", "component", component).increment();
        latency.putIfAbsent(component, channelTimeoutMs);
        return new ChannelResult(List.of());
    }

    private boolean shouldRerank(List<ScoredChunk> vector, List<ScoredChunk> lexical,
            List<ScoredChunk> fused, String query) {
        if (fused.size() >= 8) return true;
        if (query != null && (query.contains("？") || query.contains("?") || query.contains("是否"))) return true;
        if (fused.size() < 2) return false;
        double first = fused.get(0).score();
        double second = fused.get(1).score();
        boolean smallGap = first <= 0 || (first - second) / first < .10;
        long overlap = vector.stream().map(ScoredChunk::chunkId).filter(id -> lexical.stream().anyMatch(item -> item.chunkId() == id)).distinct().count();
        double overlapRatio = Math.min(vector.size(), lexical.size()) == 0
                ? 0 : (double) overlap / Math.min(vector.size(), lexical.size());
        return smallGap || overlapRatio < .40;
    }

    private List<ScoredChunk> retrieveVector(long datasetId, List<String> queries, int recall) {
        return rank(unique(queries.stream()
                .flatMap(query -> rankVector(vectors.search(new SearchRequest(datasetId, embeddings.embed(datasetId, query), recall))).stream())
                .toList()));
    }

    private List<ScoredChunk> retrieveBm25(HybridSearchRequest request, List<String> queries, int topK, int recall) {
        return rank(unique(queries.stream()
                .flatMap(query -> bm25.search(new HybridSearchRequest(request.datasetId(), query, topK,
                        request.threshold(), request.activeIndexVersions()), recall).stream())
                .toList()));
    }

    private List<ScoredChunk> rankVector(List<SearchResult> results) {
        List<ScoredChunk> output = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            SearchResult item = results.get(index);
            output.add(new ScoredChunk(item.chunkId(), item.content(), item.score(), "vector", index + 1));
        }
        return List.copyOf(output);
    }

    private List<ScoredChunk> rank(List<ScoredChunk> results) {
        List<ScoredChunk> output = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            ScoredChunk item = results.get(index);
            output.add(new ScoredChunk(item.chunkId(), item.content(), item.score(), item.channel(), index + 1));
        }
        return List.copyOf(output);
    }

    private List<ScoredChunk> unique(List<ScoredChunk> results) {
        Map<Long, ScoredChunk> unique = new LinkedHashMap<>();
        for (ScoredChunk item : results) {
            ScoredChunk old = unique.get(item.chunkId());
            if (old == null || item.score() > old.score()) unique.put(item.chunkId(), item);
        }
        return unique.values().stream().sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()).toList();
    }

    private void add(Map<Long, Double> fused, Map<Long, String> text, List<ScoredChunk> result, double weight) {
        for (int index = 0; index < result.size(); index++) {
            ScoredChunk item = result.get(index);
            fused.merge(item.chunkId(), weight / (60 + index + 1), Double::sum);
            text.put(item.chunkId(), item.content());
        }
    }

    private List<ScoredChunk> threshold(List<ScoredChunk> results, double threshold, boolean rerankApplied) {
        if (threshold <= 0) return results;
        if (rerankApplied) return results.stream().filter(item -> item.score() >= threshold).toList();
        double max = results.stream().mapToDouble(ScoredChunk::score).max().orElse(0);
        if (max <= 0) return List.of();
        return results.stream().filter(item -> item.score() / max >= threshold).toList();
    }

    private List<ScoredChunk> selectContext(List<ScoredChunk> results, int topK, boolean rerankApplied) {
        if (results.isEmpty()) return List.of();
        int limit = Math.min(topK, results.size());
        ScoredChunk best = results.get(0);
        if (limit == 1) return List.of(best);
        // RRF ranks candidates but does not make the first candidate sufficient evidence.
        // Preserve a small, bounded set until a dedicated reranker is available.
        if (!rerankApplied) return results.stream().limit(Math.min(limit, 3)).toList();
        double second = results.size() > 1 ? results.get(1).score() : 0;
        double relativeGap = best.score() == 0 ? 0 : (best.score() - second) / Math.abs(best.score());
        if (best.score() >= 4 && relativeGap >= .08) return List.of(best);
        double cutoff = Math.max(best.score() * .92, best.score() - 1.2);
        List<ScoredChunk> selected = results.stream().limit(limit).filter(item -> item.score() >= cutoff).toList();
        return selected.isEmpty() ? List.of(best) : selected;
    }

    private void record(String stage, int count) {
        metrics.counter("modelrag.retrieval.candidates", "stage", stage).increment(count);
        if (count == 0) metrics.counter("modelrag.retrieval.empty", "stage", stage).increment();
    }

    private record ChannelResult(List<ScoredChunk> results) {}
    @FunctionalInterface private interface ChannelSupplier { List<ScoredChunk> get(); }

    private void submitShadow(HybridSearchRequest request, List<ScoredChunk> v1Results, long v1LatencyMs) {
        if (!shadowEnabled || shadowComparator == null) return;
        try {
            shadowExecutor.execute(() -> {
                com.modelrag.search.shadow.RetrievalShadowComparator comparator;
                try {
                    comparator = shadowComparator.getIfAvailable();
                } catch (RuntimeException error) {
                    metrics.counter("modelrag.retrieval.shadow.v2.failure").increment();
                    return;
                }
                if (comparator == null) return;
                try {
                    comparator.compare(request, v1Results, v1LatencyMs);
                } catch (RuntimeException ignored) {
                    // RetrievalShadowComparator records its own failure; shadow work is non-critical.
                }
            });
        } catch (RejectedExecutionException rejected) {
            metrics.counter("modelrag.retrieval.shadow.v2.dropped").increment();
        }
    }
}
