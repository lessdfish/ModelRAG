package com.modelrag.indexing.pipeline;

import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.event.IndexingCompletedEvent;
import com.modelrag.common.exception.SafeErrorSummary;
import com.modelrag.common.event.ReembedDatasetEvent;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.sse.SseEmitterService;
import com.modelrag.common.vector.VectorDocument;
import com.modelrag.common.vector.VectorStore;
import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.knowledge.service.ObjectStorageService;
import com.modelrag.knowledge.splitter.RecursiveCharSplitter;
import java.util.ArrayList;
import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class IndexingPipeline {
    private static final Logger LOG = LoggerFactory.getLogger(IndexingPipeline.class);
    private static final int PARENT_TOKEN_BUDGET = 1800;
    private final KnowledgeStore store;
    private final EmbeddingService embed;
    private final VectorStore vectors;
    private final ApplicationEventPublisher events;
    private final SseEmitterService sse;
    private final IndexOutbox outbox;
    private final ObjectStorageService storage;
    private final TransactionOperations transaction;
    private final ExecutorService embeddingExecutor;
    private final RecursiveCharSplitter splitter = new RecursiveCharSplitter();

    public IndexingPipeline(KnowledgeStore store, EmbeddingService embed, VectorStore vectors,
            ApplicationEventPublisher events, SseEmitterService sse, IndexOutbox outbox, ObjectStorageService storage,
            TransactionOperations transaction,
            @org.springframework.beans.factory.annotation.Qualifier("embeddingExecutor") ExecutorService embeddingExecutor) {
        this.store = store;
        this.embed = embed;
        this.vectors = vectors;
        this.events = events;
        this.sse = sse;
        this.outbox = outbox;
        this.storage = storage;
        this.transaction = transaction;
        this.embeddingExecutor = embeddingExecutor;
    }

    @EventListener
    public void reembed(ReembedDatasetEvent event) {
        rebuildDataset(event.datasetId());
    }

    public int reembedDataset(long datasetId) {
        List<Chunk> chunks = store.chunks(datasetId);
        int total = 0;
        for (int start = 0; start < chunks.size(); start += 64) {
            List<Chunk> window = chunks.subList(start, Math.min(chunks.size(), start + 64));
            vectors.upsert(embedChunks(datasetId, window));
            total += window.size();
        }
        return total;
    }

    public int rebuildDataset(long datasetId) {
        List<Document> documents = store.documents(datasetId);
        documents.forEach(d -> index(d.id()));
        return documents.size();
    }

    public void index(long id) {
        try {
            long indexVersion = store.beginIndexVersion(id);
            Document document = store.document(id);
            Dataset dataset = store.dataset(document.datasetId());
            status(id, "PARSING", null, 0);
            status(id, "CHUNKING", null, 0);
            Map<String, String> documentMetadata = metadata(document, indexVersion);
            store.beginChunks(id, indexVersion);
            IndexState state = new IndexState(document.fileName());
            try (Reader content = content(document)) {
                splitter.forEachWindow(content, dataset.chunkSize(), dataset.chunkOverlap(), 64, parts -> {
                    List<Chunk> chunks = new ArrayList<>(parts.size());
                    List<PendingOutbox> pendingOutbox = new ArrayList<>(parts.size());
                    for (String rawPart : parts) {
                        String part = rawPart.replaceAll("\\[\\[MODELRAG_PAGE:\\d+]]", "").trim();
                        if (part.isBlank()) continue;
                        long chunkId = store.nextId();
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
                        chunks.add(chunk);
                        pendingOutbox.add(new PendingOutbox(chunk.datasetId(), chunk.documentId(), chunk.id(), json(chunk, document)));
                    }
                    List<VectorDocument> docs = embedChunks(document.datasetId(), chunks);
                    Runnable persist = () -> {
                        store.appendChunks(id, chunks);
                        vectors.upsert(docs);
                        pendingOutbox.forEach(event -> outbox.append("UPSERT_CHUNK", event.datasetId(), event.documentId(),
                                event.chunkId(), event.payload()));
                    };
                    transaction.executeWithoutResult(ignored -> persist.run());
                    state.totalChunks += chunks.size();
                    status(id, "INDEXING", null, state.totalChunks);
                });
            }
            status(id, "VECTOR_READY", null, state.totalChunks);
            status(id, "SEARCH_SYNCING", null, state.totalChunks);
            if (state.totalChunks == 0) store.activateIndexVersion(id, indexVersion);
            events.publishEvent(new IndexingCompletedEvent(this, id, true, null));
        } catch (Exception error) {
            LOG.error("Document indexing failed: documentId={}", id, error);
            String safeError = SafeErrorSummary.of(error);
            try {
                status(id, "FAILED", safeError, 0);
            } catch (Exception statusError) {
                LOG.error("Unable to persist FAILED indexing status: documentId={}", id, statusError);
            }
            events.publishEvent(new IndexingCompletedEvent(this, id, false, safeError));
        }
    }

    private record PendingOutbox(long datasetId, long documentId, long chunkId, String payload) {
    }

    private static final class IndexState {
        private String currentTitlePath;
        private String currentPage;
        private Long parentId;
        private int chunkIndex;
        private int totalChunks;
        private int parentTokens;

        private IndexState(String currentTitlePath) {
            this.currentTitlePath = currentTitlePath;
        }
    }

    private int tokenEstimate(String value) {
        double tokens = 0;
        for (int index = 0; index < value.length(); index++) {
            tokens += Character.UnicodeScript.of(value.charAt(index)) == Character.UnicodeScript.HAN ? 1 : .25;
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private List<VectorDocument> embedChunks(long datasetId, List<Chunk> chunks) {
        List<VectorDocument> result = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += 64) {
            List<Chunk> window = chunks.subList(start, Math.min(chunks.size(), start + 64));
            List<java.util.concurrent.Future<List<float[]>>> futures = new ArrayList<>();
            for (int batchStart = 0; batchStart < window.size(); batchStart += 32) {
                List<Chunk> batch = window.subList(batchStart, Math.min(window.size(), batchStart + 32));
                futures.add(embeddingExecutor.submit(
                        () -> embed.embedBatch(datasetId, batch.stream().map(Chunk::content).toList())));
                if (futures.size() == 2 || batchStart + 32 >= window.size()) {
                    for (int futureIndex = 0; futureIndex < futures.size(); futureIndex++) {
                        List<float[]> vectors = get(futures.get(futureIndex));
                        int offset = futureIndex * 32;
                        List<Chunk> completed = window.subList(offset, Math.min(window.size(), offset + vectors.size()));
                        for (int index = 0; index < vectors.size(); index++) {
                            Chunk chunk = completed.get(index);
                            result.add(new VectorDocument(chunk.id(), chunk.documentId(), chunk.datasetId(),
                                    chunk.content(), vectors.get(index), chunk.metadata()));
                        }
                    }
                    futures.clear();
                }
            }
        }
        return List.copyOf(result);
    }

    private List<float[]> get(java.util.concurrent.Future<List<float[]>> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 批处理被中断", error);
        } catch (java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Embedding 批处理失败", cause);
        }
    }

    private void status(long id, String value, String error, int chunks) {
        store.status(id, value, error, chunks);
        sse.publish("document:" + id, new SseEvent(value, error == null ? value : error,
                Map.of("documentId", id, "status", value, "chunkCount", chunks)));
    }

    private Reader content(Document document) {
        if (document.content() != null && !document.content().isBlank()) return new StringReader(document.content());
        if (document.artifactObjectKey() == null || document.artifactObjectKey().isBlank()) {
            throw new IllegalStateException("文档 artifact 不存在");
        }
        try {
            return new java.io.BufferedReader(new java.io.InputStreamReader(storage.open(document.artifactObjectKey()),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("无法读取文档 artifact", error);
        }
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

    private String parser(String fileType) {
        return switch (fileType) {
            case "PDF" -> "pdfbox";
            case "DOCX" -> "poi";
            case "MD" -> "markdown";
            case "TXT" -> "text";
            default -> fileType.toLowerCase();
        };
    }

    private String charset(String fileType) {
        return "MD".equals(fileType) || "TXT".equals(fileType) ? "UTF-8" : "extracted-text";
    }

    private String titleIn(String content) {
        if (content == null || content.isBlank()) return null;
        String title = null;
        for (String line : content.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("#")) {
                String heading = value.replaceFirst("^#+\\s*", "").trim();
                if (!heading.isBlank()) title = heading;
            } else if (value.matches("[一二三四五六七八九十0-9]+[、.．].{2,80}")) {
                title = value;
            }
        }
        return title;
    }

    private String pageIn(String content) {
        if (content == null || content.isBlank()) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\[\\[MODELRAG_PAGE:(\\d+)]]").matcher(content);
        String page = null;
        while (matcher.find()) page = matcher.group(1);
        return page;
    }

    private String json(Chunk chunk, Document document) {
        return "{\"chunkId\":" + chunk.id()
                + ",\"datasetId\":" + chunk.datasetId()
                + ",\"documentId\":" + chunk.documentId()
                + ",\"parentChunkId\":" + chunk.parentChunkId()
                + ",\"chunkIndex\":" + chunk.index()
                + ",\"version\":" + number(chunk.metadata().get("version"), 1)
                + ",\"indexType\":\"" + escape(chunk.metadata().getOrDefault("indexType", "default")) + "\""
                + ",\"titlePath\":\"" + escape(chunk.metadata().getOrDefault("titlePath", document.fileName())) + "\""
                + ",\"documentName\":\"" + escape(document.fileName()) + "\""
                + ",\"content\":\"" + escape(chunk.content()) + "\""
                + ",\"metadata\":" + metadataJson(chunk.metadata())
                + "}";
    }

    private String metadataJson(Map<String, String> metadata) {
        return metadata.entrySet().stream()
                .map(e -> "\"" + escape(e.getKey()) + "\":\"" + escape(e.getValue()) + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private int number(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String escape(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
