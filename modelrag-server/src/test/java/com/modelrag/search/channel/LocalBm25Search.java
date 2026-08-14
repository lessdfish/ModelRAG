package com.modelrag.search.channel;

import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("test")
public class LocalBm25Search implements Bm25Search {
    private static final Pattern LATIN_OR_NUMBER = Pattern.compile("[a-zA-Z0-9_\\-]{2,}|\\d+");
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private final KnowledgeStore store;
    public LocalBm25Search(KnowledgeStore store) { this.store = store; }
    public List<ScoredChunk> search(HybridSearchRequest request, int recallSize) {
        List<Chunk> chunks = store.chunks(request.datasetId()); if (chunks.isEmpty()) return List.of();
        List<String> queryTerms = unique(tokens(request.query())); if (queryTerms.isEmpty()) return List.of();
        Map<Long, List<String>> documentTerms = new HashMap<>(); Map<String, Integer> documentFrequency = new HashMap<>(); double totalLength = 0;
        for (Chunk chunk : chunks) { List<String> terms = tokens(chunk.content()); documentTerms.put(chunk.id(), terms); totalLength += terms.size(); for (String term : new LinkedHashSet<>(terms)) documentFrequency.merge(term, 1, Integer::sum); }
        double averageLength = Math.max(1, totalLength / chunks.size()); int totalDocuments = chunks.size();
        return chunks.stream().map(chunk -> new ScoredChunk(chunk.id(), chunk.content(), bm25(queryTerms, documentTerms.getOrDefault(chunk.id(), List.of()), documentFrequency, totalDocuments, averageLength), "bm25", 0)).filter(result -> result.score() > 0).sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()).limit(recallSize).toList();
    }
    private double bm25(List<String> queryTerms, List<String> documentTerms, Map<String, Integer> documentFrequency, int totalDocuments, double averageLength) { if (documentTerms.isEmpty()) return 0; Map<String, Integer> frequency = new HashMap<>(); for (String term : documentTerms) frequency.merge(term, 1, Integer::sum); double score = 0; for (String term : queryTerms) { int tf = frequency.getOrDefault(term, 0); if (tf == 0) continue; int df = documentFrequency.getOrDefault(term, 0); double idf = Math.log(1 + (totalDocuments - df + .5) / (df + .5)); double denominator = tf + K1 * (1 - B + B * documentTerms.size() / averageLength); score += idf * tf * (K1 + 1) / denominator; } return score; }
    private List<String> unique(List<String> terms) { return new ArrayList<>(new LinkedHashSet<>(terms)); }
    private List<String> tokens(String value) { String text = value == null ? "" : value.toLowerCase(Locale.ROOT); List<String> result = new ArrayList<>(); Matcher matcher = LATIN_OR_NUMBER.matcher(text); while (matcher.find()) result.add(matcher.group()); String compact = text.replaceAll("[\\s，。！？、：:；;（）()【】\\[\\]{}《》“”\"'`~!@#$%^&*+=|\\\\/<>,.?-]+", ""); for (int i = 0; i + 1 < compact.length(); i++) result.add(compact.substring(i, i + 2)); if (compact.length() == 1) result.add(compact); return result; }
}
