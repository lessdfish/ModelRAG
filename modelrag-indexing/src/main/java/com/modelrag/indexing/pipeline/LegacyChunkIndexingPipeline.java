package com.modelrag.indexing.pipeline;

import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.event.IndexingCompletedEvent;
import com.modelrag.common.exception.SafeErrorSummary;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.vector.VectorDocument;
import com.modelrag.common.vector.VectorStore;
import com.modelrag.api.TextEmbeddingProvider;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.IndexVersionRepository;
import com.modelrag.knowledge.service.ObjectStorageService;
import com.modelrag.knowledge.splitter.RecursiveCharSplitter;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Extracted V1 indexing behavior. It intentionally remains chunk and active-index-version based. */
@Service
public class LegacyChunkIndexingPipeline {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyChunkIndexingPipeline.class);
    private static final int PARENT_TOKEN_BUDGET = 1800;
    private final DatasetRepository datasets;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final IndexVersionRepository versions;
    private final TextEmbeddingProvider embed;
    private final VectorStore vectors;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final com.modelrag.common.sse.SseEmitterService sse;
    private final IndexOutbox outbox;
    private final ObjectStorageService storage;
    private final TransactionOperations transaction;
    private final ExecutorService embeddingExecutor;
    private final RecursiveCharSplitter splitter = new RecursiveCharSplitter();

    public LegacyChunkIndexingPipeline(DatasetRepository datasets, DocumentRepository documents, ChunkRepository chunks,
            IndexVersionRepository versions, TextEmbeddingProvider embed, VectorStore vectors,
            org.springframework.context.ApplicationEventPublisher events,
            com.modelrag.common.sse.SseEmitterService sse, IndexOutbox outbox, ObjectStorageService storage,
            TransactionOperations transaction,
            @Qualifier("embeddingExecutor") ExecutorService embeddingExecutor) {
        this.datasets = datasets;
        this.documents = documents;
        this.chunks = chunks;
        this.versions = versions;
        this.embed = embed;
        this.vectors = vectors;
        this.events = events;
        this.sse = sse;
        this.outbox = outbox;
        this.storage = storage;
        this.transaction = transaction;
        this.embeddingExecutor = embeddingExecutor;
    }

    public int reembedDataset(long datasetId) {
        List<Chunk> values = chunks.findActiveByDatasetId(datasetId);
        int total = 0;
        for (int start = 0; start < values.size(); start += 64) {
            List<Chunk> window = values.subList(start, Math.min(values.size(), start + 64));
            vectors.upsert(embedChunks(datasetId, window));
            total += window.size();
        }
        return total;
    }

    public int rebuildDataset(long datasetId) {
        List<Document> values = documents.findByDatasetId(datasetId);
        values.forEach(document -> index(document.id()));
        return values.size();
    }

    public LegacyIndexResult index(long id) {
        long indexVersion = 0;
        int totalChunks = 0;
        try {
            indexVersion = versions.begin(id);
            Document document = documents.findById(id);
            Dataset dataset = datasets.findById(document.datasetId());
            status(id, "PARSING", null, 0);
            status(id, "CHUNKING", null, 0);
            Map<String, String> documentMetadata = metadata(document, indexVersion);
            chunks.beginDocumentVersion(id, indexVersion);
            IndexState state = new IndexState(document.fileName());
            try (Reader content = content(document)) {
                splitter.forEachWindow(content, dataset.chunkSize(), dataset.chunkOverlap(), 64, parts -> {
                    List<Chunk> chunkBatch = new ArrayList<>(parts.size());
                    List<PendingOutbox> pendingOutbox = new ArrayList<>(parts.size());
                    for (String rawPart : parts) {
                        String part = rawPart.replaceAll("\\[\\[MODELRAG_PAGE:\\d+]]", "").trim();
                        if (part.isBlank()) continue;
                        long chunkId = chunks.nextId();
                        String detectedTitle = titleIn(part);
                        if (detectedTitle != null && !detectedTitle.equals(state.currentTitlePath)) {
                            state.currentTitlePath = detectedTitle;
                            state.parentId = null;
                            state.parentTokens = 0;
                        }
                        int childTokens = tokenEstimate(part);
                        if (state.parentId == null || (state.parentTokens > 0
                                && state.parentTokens + childTokens > PARENT_TOKEN_BUDGET)) {
                            state.parentId = chunkId;
                            state.parentTokens = 0;
                        }
                        Map<String, String> chunkMetadata = new LinkedHashMap<>(documentMetadata);
                        chunkMetadata.put("titlePath", state.currentTitlePath);
                        String page = pageIn(rawPart);
                        if (page != null) state.currentPage = page;
                        if (state.currentPage != null) chunkMetadata.put("page", state.currentPage);
                        Chunk chunk = new Chunk(chunkId, document.id(), document.datasetId(), state.chunkIndex++, part,
                                chunkMetadata, state.parentId);
                        state.parentTokens += childTokens;
                        chunkBatch.add(chunk);
                        pendingOutbox.add(new PendingOutbox(chunk.datasetId(), chunk.documentId(), chunk.id(),
                                json(chunk, document)));
                    }
                    List<VectorDocument> docs = embedChunks(document.datasetId(), chunkBatch);
                    Runnable persist = () -> {
                        chunks.append(id, chunkBatch);
                        vectors.upsert(docs);
                        pendingOutbox.forEach(event -> outbox.append("UPSERT_CHUNK", event.datasetId(),
                                event.documentId(), event.chunkId(), event.payload()));
                    };
                    transaction.executeWithoutResult(ignored -> persist.run());
                    state.totalChunks += chunkBatch.size();
                    status(id, "INDEXING", null, state.totalChunks);
                });
            }
            status(id, "VECTOR_READY", null, state.totalChunks);
            status(id, "SEARCH_SYNCING", null, state.totalChunks);
            if (state.totalChunks == 0) versions.activate(id, indexVersion);
            events.publishEvent(new IndexingCompletedEvent(this, id, true, null));
            return new LegacyIndexResult(true, indexVersion, state.totalChunks, null);
        } catch (Exception error) {
            LOG.error("Document indexing failed: documentId={}", id, error);
            String safeError = SafeErrorSummary.of(error);
            try {
                status(id, "FAILED", safeError, totalChunks);
            } catch (Exception statusError) {
                LOG.error("Unable to persist FAILED indexing status: documentId={}", id, statusError);
            }
            events.publishEvent(new IndexingCompletedEvent(this, id, false, safeError));
            return new LegacyIndexResult(false, indexVersion, totalChunks, safeError);
        }
    }

    private record PendingOutbox(long datasetId, long documentId, long chunkId, String payload) { }

    private static final class IndexState {
        private String currentTitlePath;
        private String currentPage;
        private Long parentId;
        private int chunkIndex;
        private int totalChunks;
        private int parentTokens;
        private IndexState(String currentTitlePath) { this.currentTitlePath = currentTitlePath; }
    }

    private List<VectorDocument> embedChunks(long datasetId, List<Chunk> values) {
        List<VectorDocument> result = new ArrayList<>(values.size());
        for (int start = 0; start < values.size(); start += 64) {
            List<Chunk> window = values.subList(start, Math.min(values.size(), start + 64));
            List<java.util.concurrent.Future<List<float[]>>> futures = new ArrayList<>();
            for (int batchStart = 0; batchStart < window.size(); batchStart += 32) {
                List<Chunk> batch = window.subList(batchStart, Math.min(window.size(), batchStart + 32));
                futures.add(embeddingExecutor.submit(
                        () -> embed.embedBatch(datasetId, batch.stream().map(Chunk::content).toList())));
                if (futures.size() == 2 || batchStart + 32 >= window.size()) {
                    for (int futureIndex = 0; futureIndex < futures.size(); futureIndex++) {
                        List<float[]> embeddings = get(futures.get(futureIndex));
                        int offset = futureIndex * 32;
                        List<Chunk> completed = window.subList(offset, Math.min(window.size(), offset + embeddings.size()));
                        for (int index = 0; index < embeddings.size(); index++) {
                            Chunk chunk = completed.get(index);
                            result.add(new VectorDocument(chunk.id(), chunk.documentId(), chunk.datasetId(),
                                    chunk.content(), embeddings.get(index), chunk.metadata()));
                        }
                    }
                    futures.clear();
                }
            }
        }
        return List.copyOf(result);
    }

    private List<float[]> get(java.util.concurrent.Future<List<float[]>> future) {
        try { return future.get(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Embedding 批处理被中断", error); }
        catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Embedding 批处理失败", cause);
        }
    }

    private void status(long id, String value, String error, int chunksCount) {
        documents.updateStatus(id, value, error, chunksCount);
        sse.publish("document:" + id, new SseEvent(value, error == null ? value : error,
                Map.of("documentId", id, "status", value, "chunkCount", chunksCount)));
    }

    private Reader content(Document document) {
        if (document.content() != null && !document.content().isBlank()) return new StringReader(document.content());
        if (document.artifactObjectKey() == null || document.artifactObjectKey().isBlank()) {
            throw new IllegalStateException("文档 artifact 不存在");
        }
        try {
            return new java.io.BufferedReader(new java.io.InputStreamReader(storage.open(document.artifactObjectKey()),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException error) { throw new IllegalStateException("无法读取文档 artifact", error); }
    }

    private Map<String, String> metadata(Document document, long indexVersion) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("parser", parser(document.fileType()));
        values.put("charset", charset(document.fileType()));
        values.put("titlePath", document.fileName());
        values.put("version", String.valueOf(indexVersion));
        values.put("indexType", "default");
        return values;
    }

    private String parser(String fileType) { return switch (fileType) {
        case "PDF" -> "pdfbox"; case "DOCX" -> "poi"; case "MD" -> "markdown"; case "TXT" -> "text";
        default -> fileType.toLowerCase();
    }; }

    private String charset(String fileType) { return "MD".equals(fileType) || "TXT".equals(fileType) ? "UTF-8" : "extracted-text"; }

    private int tokenEstimate(String value) {
        double tokens = 0;
        for (int index = 0; index < value.length(); index++) tokens += Character.UnicodeScript.of(value.charAt(index)) == Character.UnicodeScript.HAN ? 1 : .25;
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private String titleIn(String content) {
        if (content == null || content.isBlank()) return null;
        String title = null;
        for (String line : content.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("#")) {
                String heading = value.replaceFirst("^#+\\s*", "");
                if (!heading.isBlank()) title = heading;
            } else if (value.matches("[一二三四五六七八九十0-9]+[、.．].{2,80}")) title = value;
        }
        return title;
    }

    private String pageIn(String content) {
        if (content == null || content.isBlank()) return null;
        var matcher = java.util.regex.Pattern.compile("\\[\\[MODELRAG_PAGE:(\\d+)]]").matcher(content);
        String page = null;
        while (matcher.find()) page = matcher.group(1);
        return page;
    }

    private String json(Chunk chunk, Document document) {
        return "{\"chunkId\":" + chunk.id() + ",\"datasetId\":" + chunk.datasetId()
                + ",\"documentId\":" + chunk.documentId() + ",\"parentChunkId\":" + chunk.parentChunkId()
                + ",\"chunkIndex\":" + chunk.index() + ",\"version\":" + number(chunk.metadata().get("version"), 1)
                + ",\"indexType\":\"" + escape(chunk.metadata().getOrDefault("indexType", "default")) + "\""
                + ",\"titlePath\":\"" + escape(chunk.metadata().getOrDefault("titlePath", document.fileName())) + "\""
                + ",\"documentName\":\"" + escape(document.fileName()) + "\",\"content\":\""
                + escape(chunk.content()) + "\",\"metadata\":" + metadataJson(chunk.metadata()) + "}";
    }

    private String metadataJson(Map<String, String> metadata) {
        return metadata.entrySet().stream().map(e -> "\"" + escape(e.getKey()) + "\":\"" + escape(e.getValue()) + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private int number(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; } }
    private String escape(String value) { return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
}
