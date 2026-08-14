package com.modelrag.indexing.store;

import com.modelrag.common.vector.SearchRequest;
import com.modelrag.common.vector.SearchResult;
import com.modelrag.common.vector.VectorDocument;
import com.modelrag.common.vector.VectorStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("test")
public class InMemoryVectorStore implements VectorStore {
    private final Map<Long, VectorDocument> values = new ConcurrentHashMap<>();
    public void upsert(List<VectorDocument> docs) { docs.forEach(d -> values.put(d.id(), d)); }
    public void deleteDocument(long id) { values.entrySet().removeIf(e -> e.getValue().documentId() == id); }
    public List<SearchResult> search(SearchRequest req) { return values.values().stream().filter(v -> v.datasetId() == req.datasetId())
            .map(v -> new SearchResult(v.id(), v.content(), dot(v.embedding(), req.embedding()), "vector"))
            .sorted(Comparator.comparingDouble(SearchResult::score).reversed()).limit(req.topK()).toList(); }
    private double dot(float[] a, float[] b) { double score = 0; for (int i = 0; i < Math.min(a.length, b.length); i++) score += a[i] * b[i]; return score; }
}
