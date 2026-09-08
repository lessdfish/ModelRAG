package com.modelrag.qa.orchestrator;

import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.ChunkWindow;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Owns hybrid retrieval post-processing: MMR, parent expansion and evidence assembly. */
@Service
public class RetrievalPipeline {
    private static final double MMR_LAMBDA = 0.75;
    private final SearchFacade search;
    private final ChunkRepository chunks;

    public RetrievalPipeline(SearchFacade search, ChunkRepository chunks) {
        this.search = search;
        this.chunks = chunks;
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
        if (selected.isEmpty()) return List.of();
        Set<Long> selectedIds = selected.stream().map(ScoredChunk::chunkId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, Chunk> byId = chunks.findActiveByIds(datasetId, selectedIds).stream()
                .collect(Collectors.toMap(Chunk::id, chunk -> chunk, (a, b) -> a));
        Set<Long> parentIds = byId.values().stream().map(Chunk::parentChunkId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, List<Chunk>> byParent = chunks.findActiveByParentIds(datasetId, parentIds).stream()
                .filter(chunk -> chunk.parentChunkId() != null)
                .collect(Collectors.groupingBy(Chunk::parentChunkId, java.util.LinkedHashMap::new, Collectors.toList()));
        List<ChunkWindow> windows = byId.values().stream()
                .filter(chunk -> chunk.parentChunkId() == null)
                .map(chunk -> new ChunkWindow(chunk.documentId(), Math.max(0, chunk.index() - 1), chunk.index() + 1))
                .toList();
        Map<String, List<Chunk>> byPosition = chunks.findActiveNeighbors(datasetId, windows).stream()
                .collect(Collectors.groupingBy(chunk -> chunk.documentId() + ":" + chunk.index(),
                        java.util.LinkedHashMap::new, Collectors.toList()));
        List<ScoredChunk> expanded = new ArrayList<>();
        Set<Long> emittedChunkIds = new LinkedHashSet<>();
        for (ScoredChunk scored : selected) {
            Chunk center = byId.get(scored.chunkId());
            if (center == null) {
                // The bounded lookup is also the visibility check. Do not fall back to
                // search-result text when the chunk is no longer in the active index.
                continue;
            }
            if (center.parentChunkId() != null) {
                List<Chunk> parentChunks = uniqueChunks(byParent.get(center.parentChunkId()), center.documentId());
                String parentContent = content(parentChunks);
                if (!parentContent.isBlank()) {
                    addFreshContext(expanded, scored, parentChunks, emittedChunkIds);
                    continue;
                }
            }
            List<Chunk> neighbors = new ArrayList<>();
            for (int offset = -1; offset <= 1; offset++) {
                neighbors.addAll(byPosition.getOrDefault(center.documentId() + ":" + (center.index() + offset), List.of()));
            }
            addFreshContext(expanded, scored, uniqueChunks(neighbors, center.documentId()), emittedChunkIds);
        }
        return List.copyOf(expanded);
    }

    private void addFreshContext(List<ScoredChunk> expanded, ScoredChunk scored, List<Chunk> chunks,
            Set<Long> emittedChunkIds) {
        List<Chunk> fresh = chunks.stream().filter(chunk -> emittedChunkIds.add(chunk.id())).toList();
        if (!fresh.isEmpty()) {
            expanded.add(new ScoredChunk(scored.chunkId(), content(fresh), scored.score(), scored.channel(), scored.rank()));
        }
    }

    private List<Chunk> uniqueChunks(Collection<Chunk> chunks, long documentId) {
        if (chunks == null) return List.of();
        Map<Long, Chunk> unique = chunks.stream()
                .filter(chunk -> chunk.documentId() == documentId)
                .collect(Collectors.toMap(Chunk::id, chunk -> chunk, (a, b) -> a,
                        java.util.LinkedHashMap::new));
        return unique.values().stream().sorted(Comparator.comparingInt(Chunk::index)).toList();
    }

    private String content(Collection<Chunk> chunks) {
        return chunks.stream()
                .map(Chunk::content).collect(Collectors.joining("\n"));
    }

    public record RetrievalResult(SearchStages stages, List<ScoredChunk> selected, List<ScoredChunk> contextChunks) { }
}
