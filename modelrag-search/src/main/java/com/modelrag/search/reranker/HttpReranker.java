package com.modelrag.search.reranker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelHealthRegistry;
import com.modelrag.inference.rerank.RerankComputeProvider;
import com.modelrag.inference.rerank.RerankDocument;
import com.modelrag.inference.rerank.RerankScore;
import com.modelrag.search.dto.ScoredChunk;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Applies the optional remote cross-encoder and retains the local degraded path. */
@Service
public class HttpReranker implements Reranker {
    private static final Pattern FAQ = Pattern.compile("问[:：]\\s*([^？?\\n]+)[？?]\\s*答[:：]\\s*([^。！？\\n]+)");
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9_\\-]{2,}|\\d+");
    private static final int MAX_CANDIDATES = 100;
    private final boolean enabled;
    private final String model;
    private final long remoteTimeoutMs;
    private final RerankComputeProvider compute;
    private final TokenUsageTracker tokens;
    private final ObjectProvider<ModelHealthRegistry> health;

    @Autowired
    public HttpReranker(@Value("${modelrag.reranker.enabled:false}") boolean enabled,
            @Value("${modelrag.reranker.model:configured-reranker-profile}") String model,
            @Value("${modelrag.reranker.remote-timeout-ms:30000}") long remoteTimeoutMs,
            RerankComputeProvider compute,
            TokenUsageTracker tokens, ObjectProvider<ModelHealthRegistry> health) {
        this.enabled = enabled;
        this.model = model == null || model.isBlank() ? "configured-reranker-profile" : model.trim();
        this.remoteTimeoutMs = Math.max(1, remoteTimeoutMs);
        this.compute = compute;
        this.tokens = tokens;
        this.health = health;
    }

    /** Compatibility constructor retained for callers that used the pre-G10 HTTP adapter. */
    public HttpReranker(boolean enabled, String ignoredUrl, ObjectMapper ignoredJson,
            TokenUsageTracker tokens, ObjectProvider<ModelHealthRegistry> health) {
        this(enabled, "configured-reranker-profile", 500,
                (ignoredModel, ignoredQuery, ignoredDocuments, ignoredTimeout) -> {
                    throw new IllegalStateException("remote reranker is not configured");
                }, tokens, health);
    }

    @Override public List<ScoredChunk> rerank(long datasetId, String query, List<ScoredChunk> candidates) {
        return rerank(datasetId, query, candidates, Duration.ofMillis(500));
    }

    @Override public List<ScoredChunk> rerank(long datasetId, String query, List<ScoredChunk> candidates,
            Duration timeout) {
        if (candidates == null || candidates.isEmpty()) return candidates == null ? List.of() : candidates;
        if (!enabled) return localRerank(query, candidates);
        if (candidates.size() > MAX_CANDIDATES) return localRerank(query, candidates);
        String modelName = "remote-reranker-" + model;
        ModelHealthRegistry registry = health.getIfAvailable();
        if (registry != null && !registry.available("RERANK", modelName)) return localRerank(query, candidates);
        try {
            Duration effectiveTimeout = effectiveTimeout(timeout);
            List<RerankDocument> documents = IntStream.range(0, candidates.size())
                    .mapToObj(index -> new RerankDocument(Integer.toString(index), candidates.get(index).content()))
                    .toList();
            List<RerankScore> scores = compute.rerank(model, query, documents, effectiveTimeout);
            MapScores mapped = validateScores(scores, candidates.size());
            tokens.recordRerank(datasetId);
            if (registry != null) registry.success("RERANK", modelName);
            return IntStream.range(0, candidates.size()).mapToObj(index -> {
                ScoredChunk candidate = candidates.get(index);
                return new ScoredChunk(candidate.chunkId(), candidate.content(), mapped.scores()[index], "rerank", 0);
            }).sorted(java.util.Comparator.comparingDouble(ScoredChunk::score).reversed()).toList();
        } catch (RuntimeException ignored) {
            if (registry != null) registry.failure("RERANK", modelName);
            return localRerank(query, candidates);
        }
    }
    @Override public boolean enabled(){return enabled;}

    private Duration effectiveTimeout(Duration requested) {
        long requestedMs = requested == null ? remoteTimeoutMs : requested.toMillis();
        if (requestedMs <= 0) return Duration.ofMillis(1);
        return Duration.ofMillis(Math.max(1, Math.min(remoteTimeoutMs, requestedMs)));
    }

    private MapScores validateScores(List<RerankScore> scores, int expected) {
        if (scores == null || scores.size() != expected) throw new IllegalArgumentException("rerank response shape is invalid");
        double[] values = new double[expected];
        boolean[] seen = new boolean[expected];
        for (RerankScore score : scores) {
            if (score == null || score.id() == null || !Double.isFinite(score.score())) {
                throw new IllegalArgumentException("rerank response score is invalid");
            }
            int index;
            try {
                index = Integer.parseInt(score.id());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("rerank response id is invalid");
            }
            if (index < 0 || index >= expected || seen[index]) {
                throw new IllegalArgumentException("rerank response id is invalid");
            }
            seen[index] = true;
            values[index] = score.score();
        }
        for (boolean value : seen) if (!value) throw new IllegalArgumentException("rerank response is incomplete");
        return new MapScores(values);
    }

    private record MapScores(double[] scores) { }

    private List<ScoredChunk> localRerank(String query, List<ScoredChunk> candidates) {
        double max = candidates.stream().mapToDouble(ScoredChunk::score).max().orElse(1);
        return IntStream.range(0, candidates.size()).mapToObj(index -> {
            ScoredChunk candidate = candidates.get(index);
            double base = max <= 0 ? 0 : candidate.score() / max;
            double rankPrior = 1.0 / (index + 1);
            double relevance = relevance(query, candidate.content());
            double score = relevance * 8 + base * 4 + rankPrior * 3;
            return new ScoredChunk(candidate.chunkId(), candidate.content(), score, "local-rerank", 0);
        }).sorted(java.util.Comparator.comparingDouble(ScoredChunk::score).reversed()).toList();
    }

    private double relevance(String query, String content) {
        String q = normalize(query);
        String text = normalize(content);
        if (q.isBlank() || text.isBlank()) return 0;
        double score = 0;
        if (text.contains(q)) score += 8;
        if (q.contains(text) && text.length() >= 4) score += 4;
        score += tokenOverlap(query, content) * 2.5;
        score += pairOverlap(q, text);
        score += faqScore(q, content);
        return score;
    }

    private double faqScore(String normalizedQuery, String content) {
        Matcher matcher = FAQ.matcher(content == null ? "" : content);
        double best = 0;
        while (matcher.find()) {
            double questionScore = pairOverlap(normalizedQuery, normalize(matcher.group(1)));
            double answerScore = pairOverlap(normalizedQuery, normalize(matcher.group(2))) * 0.35;
            best = Math.max(best, questionScore + answerScore + 1);
        }
        return best;
    }

    private double pairOverlap(String query, String content) {
        Set<String> pairs = pairs(query);
        if (pairs.isEmpty()) return 0;
        double hits = 0;
        for (String pair : pairs) if (content.contains(pair)) hits++;
        return hits / pairs.size();
    }

    private int tokenOverlap(String query, String content) {
        Set<String> tokens = tokens(query);
        tokens.retainAll(tokens(content));
        return tokens.size();
    }

    private Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        Matcher matcher = TOKEN.matcher(value == null ? "" : value.toLowerCase());
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private Set<String> pairs(String value) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            String pair = value.substring(i, i + 2);
            if (!stopPair(pair)) result.add(pair);
        }
        return result;
    }

    private boolean stopPair(String pair) {
        return Set.of("的是", "什么", "多少", "有几", "几天", "怎么", "如何", "可以", "能够").contains(pair);
    }

    private String normalize(String value) {
        return (value == null ? "" : value)
                .replaceAll("(请问|请|帮我|一下|是否|吗|呢|的|了)", "")
                .replaceAll("[\\s，。！？、：:；;（）()【】\\[\\]#*`]+", "")
                .toLowerCase();
    }
}
