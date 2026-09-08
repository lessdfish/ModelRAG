package com.modelrag.knowledge.service;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("test")
public class InMemoryKnowledgeStore implements KnowledgeStore {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Dataset> datasets = new ConcurrentHashMap<>();
    private final Map<Long, Document> documents = new ConcurrentHashMap<>();
    private final Map<Long, List<Chunk>> chunks = new ConcurrentHashMap<>();

    public Dataset createDataset(String name, String description, Integer size, Integer overlap) {
        return createDataset(name, description, size, overlap, null, null);
    }

    public Dataset createDataset(String name, String description, Integer size, Integer overlap, Integer topK, Double threshold) {
        requireName(name);
        int normalizedSize = size == null ? 600 : size;
        int normalizedOverlap = overlap == null ? 80 : overlap;
        validate(normalizedSize, normalizedOverlap);
        long id = ids.incrementAndGet();
        Dataset dataset = new Dataset(id, name.trim(), description, normalizedSize, normalizedOverlap,
                topK == null ? 5 : topK, threshold == null ? .7 : threshold, 1);
        datasets.put(id, dataset);
        return dataset;
    }

    public List<Dataset> datasets() { return datasets.values().stream().sorted(Comparator.comparingLong(Dataset::id)).toList(); }

    public List<Dataset> routeDatasets(String query, Set<Long> allowedDatasetIds, int limit) {
        Set<Long> indexed = indexedDatasetIds();
        return datasets().stream()
                .filter(dataset -> allowedDatasetIds == null || allowedDatasetIds.isEmpty()
                        || allowedDatasetIds.contains(dataset.id()))
                .filter(dataset -> indexed.contains(dataset.id()))
                .sorted(Comparator.comparingInt((Dataset dataset) -> metadataScore(dataset, query)).reversed()
                        .thenComparing(Dataset::id))
                .limit(Math.max(1, Math.min(3, limit))).toList();
    }

    public Set<Long> indexedDatasetIds() {
        return chunks.values().stream().flatMap(List::stream).map(Chunk::datasetId).collect(java.util.stream.Collectors.toSet());
    }

    public Dataset dataset(long id) {
        Dataset value = datasets.get(id);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        return value;
    }

    public Dataset updateDataset(long id, String name, String description) {
        Dataset current = dataset(id);
        return updateDataset(id, name, description, current.chunkSize(), current.chunkOverlap(), current.topK(), current.threshold());
    }

    public Dataset updateDataset(long id, String name, String description, Integer size, Integer overlap, Integer topK, Double threshold) {
        Dataset current = dataset(id);
        requireName(name);
        int normalizedSize = size == null ? 600 : size;
        int normalizedOverlap = overlap == null ? 80 : overlap;
        validate(normalizedSize, normalizedOverlap);
        Dataset next = new Dataset(id, name.trim(), description, normalizedSize, normalizedOverlap,
                topK == null ? 5 : topK, threshold == null ? .7 : threshold, current.revision() + 1);
        datasets.put(id, next);
        return next;
    }

    public void deleteDataset(long id) {
        softDeleteDatasetOnly(id);
        softDeleteDocumentsByDataset(id);
        softDeleteChunksByDataset(id);
    }

    public Document addDocument(long datasetId, String name, String type, String hash, String content) {
        return addDocument(datasetId, name, type, hash, content, null, null, hash);
    }

    public Document addDocument(long datasetId, String name, String type, String hash, String content,
            String sourceKey, String artifactKey, String contentHash) {
        Document document = createDocumentOnly(datasetId, name, type, hash, content,
                sourceKey, artifactKey, contentHash);
        bumpDatasetRevision(datasetId);
        return document;
    }

    public Document createDocumentOnly(long datasetId, String name, String type, String hash, String content,
            String sourceKey, String artifactKey, String contentHash) {
        dataset(datasetId);
        if (documents.values().stream().anyMatch(document -> document.datasetId() == datasetId && hash.equals(document.hash()))) {
            throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT, "同一知识库中已有相同文档");
        }
        long id = ids.incrementAndGet();
        Document document = new Document(id, datasetId, name, type, hash, content, "PENDING", null, 0,
                sourceKey, artifactKey, contentHash);
        documents.put(id, document);
        return document;
    }

    public Document document(long id) {
        Document value = documents.get(id);
        if (value == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        return value;
    }

    public void deleteDocument(long datasetId, long documentId) {
        Document value = document(documentId);
        if (value.datasetId() != datasetId) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        softDeleteDocumentOnly(documentId);
        softDeleteChunksByDocument(documentId);
        bumpDatasetRevision(datasetId);
    }

    public void status(long id, String status, String error, int count) { documents.put(id, document(id).withStatus(status, error, count)); }
    public List<Document> documents(long datasetId) { return documents.values().stream().filter(d -> d.datasetId() == datasetId).sorted(Comparator.comparingLong(Document::id)).toList(); }
    public List<Chunk> chunks(long datasetId) { return chunks.values().stream().flatMap(List::stream).filter(c -> c.datasetId() == datasetId).toList(); }
    public List<Chunk> findChunksByIds(long datasetId, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return chunks(datasetId).stream().filter(chunk -> ids.contains(chunk.id())).toList();
    }
    public List<Chunk> findChunksByParentIds(long datasetId, Collection<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) return List.of();
        return chunks(datasetId).stream().filter(chunk -> chunk.parentChunkId() != null && parentIds.contains(chunk.parentChunkId()))
                .sorted(Comparator.comparingLong(Chunk::documentId).thenComparingInt(Chunk::index)).toList();
    }
    public List<Chunk> findChunkNeighbors(long datasetId, Collection<KnowledgeStore.ChunkWindow> windows) {
        if (windows == null || windows.isEmpty()) return List.of();
        Map<Long, Chunk> result = new LinkedHashMap<>();
        for (KnowledgeStore.ChunkWindow window : windows) {
            chunks(datasetId).stream()
                    .filter(chunk -> chunk.documentId() == window.documentId()
                            && chunk.index() >= window.fromIndex() && chunk.index() <= window.toIndex())
                    .forEach(chunk -> result.putIfAbsent(chunk.id(), chunk));
        }
        return result.values().stream().sorted(Comparator.comparingLong(Chunk::documentId).thenComparingInt(Chunk::index)).toList();
    }
    public void chunks(long documentId, List<Chunk> next) {
        replaceChunksOnly(documentId, next);
        bumpDatasetRevision(document(documentId).datasetId());
    }
    @Override public void beginChunks(long documentId, long version) { chunks.remove(documentId); }
    @Override public void appendChunks(long documentId, List<Chunk> next) {
        chunks.computeIfAbsent(documentId, ignored -> new ArrayList<>()).addAll(next);
    }
    public long nextId() { return ids.incrementAndGet(); }

    public Dataset bumpDatasetRevision(long datasetId) {
        Dataset current = dataset(datasetId);
        Dataset next = new Dataset(current.id(), current.name(), current.description(), current.chunkSize(), current.chunkOverlap(),
                current.topK(), current.threshold(), current.revision() + 1);
        datasets.put(datasetId, next);
        return next;
    }

    public void softDeleteDatasetOnly(long datasetId) {
        dataset(datasetId);
        datasets.remove(datasetId);
    }

    public void softDeleteDocumentsByDataset(long datasetId) {
        documents.values().removeIf(document -> document.datasetId() == datasetId);
    }

    public void softDeleteChunksByDataset(long datasetId) {
        chunks.entrySet().removeIf(entry -> entry.getValue().stream().anyMatch(chunk -> chunk.datasetId() == datasetId));
    }

    public void softDeleteDocumentOnly(long documentId) {
        document(documentId);
        documents.remove(documentId);
    }

    public void softDeleteChunksByDocument(long documentId) {
        chunks.remove(documentId);
    }

    public void replaceChunksOnly(long documentId, List<Chunk> next) {
        chunks.put(documentId, new ArrayList<>(next));
    }

    private int metadataScore(Dataset dataset, String query) {
        String source = query == null ? "" : query.replaceAll("[\\s，。！？、：:]+", "");
        String target = dataset.name() + " " + (dataset.description() == null ? "" : dataset.description());
        int score = 0;
        for (int index = 0; index + 1 < source.length(); index++) {
            if (target.contains(source.substring(index, index + 2))) score++;
        }
        return score;
    }

    private void requireName(String value) { if (value == null || value.isBlank()) throw new BusinessException(ErrorCode.VALIDATION, "知识库名称不能为空"); }
    private void validate(int size, int overlap) {
        if (size < 128 || size > 4096 || overlap < 0 || overlap >= size || overlap > size / 4) {
            throw new BusinessException(ErrorCode.VALIDATION, "chunk 参数无效");
        }
    }
}
