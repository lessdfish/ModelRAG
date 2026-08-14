package com.modelrag.search.reranker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.metrics.TokenUsageTracker;
import com.modelrag.common.model.ModelHealthRegistry;
import com.modelrag.search.dto.ScoredChunk;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Calls an optional local cross-encoder runtime; RRF order remains the degraded path. */
@Service
public class HttpReranker implements Reranker {
    private static final Pattern FAQ = Pattern.compile("问[:：]\\s*([^？?\\n]+)[？?]\\s*答[:：]\\s*([^。！？\\n]+)");
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9_\\-]{2,}|\\d+");
    private final boolean enabled;
    private final String url;
    private final ObjectMapper json;
    private final TokenUsageTracker tokens;
    private final ObjectProvider<ModelHealthRegistry> health;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();

    public HttpReranker(@Value("${modelrag.reranker.enabled:false}") boolean enabled,
            @Value("${modelrag.reranker.url:http://127.0.0.1:18080}") String url, ObjectMapper json,
            TokenUsageTracker tokens, ObjectProvider<ModelHealthRegistry> health) {
        this.enabled = enabled; this.url = url.replaceAll("/$", ""); this.json = json; this.tokens = tokens; this.health = health;
    }

    @Override public List<ScoredChunk> rerank(long datasetId, String query, List<ScoredChunk> candidates) {
        return rerank(datasetId, query, candidates, Duration.ofMillis(500));
    }

    @Override public List<ScoredChunk> rerank(long datasetId, String query, List<ScoredChunk> candidates,
            Duration timeout) {
        if (candidates.isEmpty()) return candidates;
        if (!enabled) return localRerank(query, candidates);
        String modelName = "http-reranker-" + url;
        ModelHealthRegistry registry = health.getIfAvailable();
        if (registry != null && !registry.available("RERANK", modelName)) return localRerank(query, candidates);
        try {
            String request = json.writeValueAsString(Map.of("query", query,
                    "documents", candidates.stream().map(ScoredChunk::content).toList()));
            long timeoutMillis = timeout == null ? 500 : Math.max(50, Math.min(500, timeout.toMillis()));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url + "/rerank"))
                    .timeout(Duration.ofMillis(timeoutMillis)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode scores = json.readTree(response.body()).path("scores");
            if (response.statusCode() / 100 != 2 || scores.size() != candidates.size()) {
                if (registry != null) registry.failure("RERANK", modelName);
                return localRerank(query, candidates);
            }
            tokens.recordRerank(datasetId);
            if (registry != null) registry.success("RERANK", modelName);
            return IntStream.range(0, candidates.size()).mapToObj(index -> {
                ScoredChunk candidate = candidates.get(index);
                return new ScoredChunk(candidate.chunkId(), candidate.content(), scores.get(index).asDouble(), "rerank", 0);
            }).sorted(java.util.Comparator.comparingDouble(ScoredChunk::score).reversed()).toList();
        } catch (Exception ignored) {
            if (registry != null) registry.failure("RERANK", modelName);
            return localRerank(query, candidates);
        }
    }
    @Override public boolean enabled(){return enabled;}

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
