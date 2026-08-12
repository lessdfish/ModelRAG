package com.modelrag.knowledge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!postgres")
public class KnowledgeStore {
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, Dataset> datasets = new ConcurrentHashMap<>();
    private final Map<Long, Document> documents = new ConcurrentHashMap<>();
    private final Map<Long, List<Chunk>> chunks = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public Dataset createDataset(String name, String description, Integer size, Integer overlap) {
        return createDataset(name, description, size, overlap, null, null);
    }

    public Dataset createDataset(String name, String description, Integer size, Integer overlap, Integer topK, Double threshold) {
        if (name == null || name.isBlank()) throw new BusinessException(ErrorCode.VALIDATION, "知识库名称不能为空");
        int chunkSize = chunkSize(size);
        int chunkOverlap = chunkOverlap(overlap, chunkSize);
        long id = ids.incrementAndGet();
        Dataset ds = new Dataset(id, name.trim(), description, chunkSize, chunkOverlap, topK(topK), threshold(threshold), 1);
        datasets.put(id, ds);
        persist();
        return ds;
    }

    public List<Dataset> datasets() {
        return datasets.values().stream().sorted(Comparator.comparingLong(Dataset::id)).toList();
    }

    public Dataset dataset(long id) {
        Dataset d = datasets.get(id);
        if (d == null) throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
        return d;
    }

    public Dataset updateDataset(long id, String name, String description) {
        Dataset current = dataset(id);
        return updateDataset(id, name, description, current.chunkSize(), current.chunkOverlap(), current.topK(), current.threshold());
    }

    public Dataset updateDataset(long id, String name, String description, Integer size, Integer overlap, Integer topK, Double threshold) {
        Dataset current = dataset(id);
        if (name == null || name.isBlank()) throw new BusinessException(ErrorCode.VALIDATION, "知识库名称不能为空");
        int chunkSize = chunkSize(size);
        int chunkOverlap = chunkOverlap(overlap, chunkSize);
        Dataset next = new Dataset(id, name.trim(), description, chunkSize, chunkOverlap, topK(topK), threshold(threshold), current.revision() + 1);
        datasets.put(id, next);
        persist();
        return next;
    }

    public void deleteDataset(long id) {
        dataset(id);
        datasets.remove(id);
        documents.values().removeIf(d -> d.datasetId() == id);
        chunks.entrySet().removeIf(entry -> entry.getValue().stream().anyMatch(c -> c.datasetId() == id));
        persist();
    }

    public Document addDocument(long datasetId, String name, String type, String hash, String content) {
        dataset(datasetId);
        if (documents.values().stream().anyMatch(d -> d.datasetId() == datasetId && d.hash().equals(hash))) {
            throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT, "同一知识库中已有相同文档");
        }
        long id = ids.incrementAndGet();
        Document d = new Document(id, datasetId, name, type, hash, content, "PENDING", null, 0);
        documents.put(id, d);
        bumpDatasetRevision(datasetId);
        return d;
    }

    public Document document(long id) {
        Document d = documents.get(id);
        if (d == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        return d;
    }

    public void deleteDocument(long datasetId, long documentId) {
        Document d = document(documentId);
        if (d.datasetId() != datasetId) throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        documents.remove(documentId);
        chunks.remove(documentId);
        bumpDatasetRevision(datasetId);
    }

    public void status(long id, String status, String error, int count) {
        Document d = document(id);
        documents.put(id, d.withStatus(status, error, count));
        persist();
    }

    public List<Document> documents(long datasetId) {
        return documents.values().stream()
                .filter(d -> d.datasetId() == datasetId)
                .sorted(Comparator.comparingLong(Document::id))
                .toList();
    }

    public List<Chunk> chunks(long datasetId) {
        return chunks.values().stream()
                .flatMap(List::stream)
                .filter(c -> c.datasetId() == datasetId)
                .toList();
    }

    public void chunks(long documentId, List<Chunk> next) {
        chunks.put(documentId, next);
        bumpDatasetRevision(document(documentId).datasetId());
    }

    public long nextId() {
        long id = ids.incrementAndGet();
        persist();
        return id;
    }

    public Dataset bumpDatasetRevision(long datasetId) {
        Dataset current = dataset(datasetId);
        Dataset next = new Dataset(current.id(), current.name(), current.description(), current.chunkSize(),
                current.chunkOverlap(), current.topK(), current.threshold(), current.revision() + 1);
        datasets.put(datasetId, next);
        persist();
        return next;
    }

    @PostConstruct
    public void load() {
        if (getClass() != KnowledgeStore.class) return;
        Path file = storageFile();
        if (!Files.exists(file)) return;
        try {
            Snapshot snapshot = json.readValue(file.toFile(), new TypeReference<>() {});
            datasets.clear();
            documents.clear();
            chunks.clear();
            datasets.putAll(snapshot.datasets() == null ? Map.of() : snapshot.datasets());
            documents.putAll(snapshot.documents() == null ? Map.of() : snapshot.documents());
            chunks.putAll(snapshot.chunks() == null ? Map.of() : snapshot.chunks());
            ids.set(Math.max(snapshot.id(), maxExistingId()));
        } catch (Exception e) {
            throw new IllegalStateException("无法加载本地知识库持久化文件: " + file, e);
        }
    }

    protected int chunkSize(Integer value) {
        int next = value == null ? 512 : value;
        if (next < 128 || next > 4096) throw new BusinessException(ErrorCode.VALIDATION, "chunkSize 必须在 128 到 4096 之间");
        return next;
    }

    protected int chunkOverlap(Integer value, int chunkSize) {
        int next = value == null ? 64 : value;
        if (next < 0 || next >= chunkSize) throw new BusinessException(ErrorCode.VALIDATION, "chunkOverlap 必须大于等于 0 且小于 chunkSize");
        return next;
    }

    protected int topK(Integer value) {
        int next = value == null ? 5 : value;
        if (next < 1 || next > 20) throw new BusinessException(ErrorCode.VALIDATION, "topK 必须在 1 到 20 之间");
        return next;
    }

    protected double threshold(Double value) {
        double next = value == null ? .7 : value;
        if (next < 0 || next > 1) throw new BusinessException(ErrorCode.VALIDATION, "threshold 必须在 0 到 1 之间");
        return next;
    }

    private synchronized void persist() {
        Path file = storageFile();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), new Snapshot(ids.get(), datasets, documents, chunks));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new IllegalStateException("无法保存本地知识库持久化文件: " + file, e);
        }
    }

    private Path storageFile() {
        return dataDir().resolve("knowledge-store.json");
    }

    private Path dataDir() {
        String configured = System.getProperty("modelrag.local.data-dir");
        if (configured == null || configured.isBlank()) configured = System.getenv("MODELRAG_LOCAL_DATA_DIR");
        if (configured == null || configured.isBlank()) configured = "tmp/local-data";
        return Path.of(configured);
    }

    private long maxExistingId() {
        long maxDataset = datasets.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        long maxDocument = documents.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        long maxChunk = chunks.values().stream().flatMap(List::stream).mapToLong(Chunk::id).max().orElse(0);
        return Math.max(maxDataset, Math.max(maxDocument, maxChunk));
    }

    private record Snapshot(long id, Map<Long, Dataset> datasets, Map<Long, Document> documents, Map<Long, List<Chunk>> chunks) {}
}
