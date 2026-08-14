package com.modelrag.qa.orchestrator;

import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Owns hybrid retrieval post-processing: MMR, parent expansion and evidence assembly. */
@Service
public class RetrievalPipeline {
    private static final double MMR_LAMBDA = 0.75;
    private final SearchFacade search;
    private final KnowledgeStore store;

    public RetrievalPipeline(SearchFacade search, KnowledgeStore store) {
        this.search = search;
        this.store = store;
    }

    public RetrievalResult retrieve(long datasetId, String query, int topK, double threshold) {
        SearchStages stages = search.inspect(new HybridSearchRequest(datasetId, query, Math.max(topK * 2, 6), threshold));
        List<ScoredChunk> selected = mmr(stages.finalResults(), topK);
        return new RetrievalResult(stages, selected, expandContext(datasetId, selected));
    }

    private List<ScoredChunk> mmr(List<ScoredChunk> candidates, int limit) {
        List<ScoredChunk> remaining = new ArrayList<>(candidates == null ? List.of() : candidates);
        List<ScoredChunk> selected = new ArrayList<>();
        double maxScore = remaining.stream().mapToDouble(ScoredChunk::score).max().orElse(1);
        while (!remaining.isEmpty() && selected.size() < limit) {
            ScoredChunk best = remaining.stream()
                    .max(Comparator.comparingDouble(candidate -> mmrScore(candidate, selected, maxScore)))
                    .orElseThrow();
            selected.add(best);
            remaining.remove(best);
        }
        return List.copyOf(selected);
    }

    private double mmrScore(ScoredChunk candidate, List<ScoredChunk> selected, double maxScore) {
        double relevance = maxScore <= 0 ? 0 : candidate.score() / maxScore;
        double redundancy = selected.stream().mapToDouble(item -> jaccard(candidate.content(), item.content())).max().orElse(0);
        return MMR_LAMBDA * relevance - (1 - MMR_LAMBDA) * redundancy;
    }

    private double jaccard(String left, String right) {
        Set<String> a = pairs(left);
        Set<String> b = pairs(right);
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> both = new HashSet<>(a);
        both.retainAll(b);
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return (double) both.size() / all.size();
    }

    private Set<String> pairs(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\s，。！？、：:；;（）()]+", "");
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < normalized.length(); i++) result.add(normalized.substring(i, i + 2));
        return result;
    }

    private List<ScoredChunk> expandContext(long datasetId, List<ScoredChunk> selected) {
        List<Chunk> all = store.chunks(datasetId);
        Map<Long, Chunk> byId = all.stream().collect(Collectors.toMap(Chunk::id, Function.identity(), (a, b) -> a));
        Map<String, Chunk> byPosition = all.stream().collect(Collectors.toMap(
                chunk -> chunk.documentId() + ":" + chunk.index(), Function.identity(), (a, b) -> a));
        List<ScoredChunk> expanded = new ArrayList<>();
        for (ScoredChunk scored : selected) {
            Chunk center = byId.get(scored.chunkId());
            if (center == null) {
                expanded.add(scored);
                continue;
            }
            if (center.parentChunkId() != null) {
                String parentContent = all.stream()
                        .filter(chunk -> center.parentChunkId().equals(chunk.parentChunkId()))
                        .filter(chunk -> chunk.documentId() == center.documentId())
                        .sorted(Comparator.comparingInt(Chunk::index))
                        .map(Chunk::content).collect(Collectors.joining("\n"));
                if (!parentContent.isBlank()) {
                    expanded.add(new ScoredChunk(scored.chunkId(), parentContent, scored.score(), scored.channel(), scored.rank()));
                    continue;
                }
            }
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            for (int offset = -1; offset <= 1; offset++) {
                Chunk adjacent = byPosition.get(center.documentId() + ":" + (center.index() + offset));
                if (adjacent != null) ids.add(adjacent.id());
            }
            String content = ids.stream().map(byId::get).filter(c -> c != null)
                    .map(Chunk::content).collect(Collectors.joining("\n"));
            expanded.add(new ScoredChunk(scored.chunkId(), content, scored.score(), scored.channel(), scored.rank()));
        }
        return List.copyOf(expanded);
    }

    public record RetrievalResult(SearchStages stages, List<ScoredChunk> selected, List<ScoredChunk> contextChunks) { }
}
