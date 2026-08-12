package com.modelrag.indexing.pipeline;

import com.modelrag.common.cache.QaAnswerCache;
import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.event.IndexingCompletedEvent;
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
import com.modelrag.knowledge.splitter.RecursiveCharSplitter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class IndexingPipeline {
    private final KnowledgeStore store;
    private final EmbeddingService embed;
    private final VectorStore vectors;
    private final ApplicationEventPublisher events;
    private final SseEmitterService sse;
    private final IndexOutbox outbox;
    private final QaAnswerCache answers;
    private final RecursiveCharSplitter splitter = new RecursiveCharSplitter();

    public IndexingPipeline(KnowledgeStore store, EmbeddingService embed, VectorStore vectors,
            ApplicationEventPublisher events, SseEmitterService sse, IndexOutbox outbox, QaAnswerCache answers) {
        this.store = store;
        this.embed = embed;
        this.vectors = vectors;
        this.events = events;
        this.sse = sse;
        this.outbox = outbox;
        this.answers = answers;
    }

    @EventListener
    public void reembed(ReembedDatasetEvent event) {
        rebuildDataset(event.datasetId());
    }

    public int reembedDataset(long datasetId) {
        List<VectorDocument> docs = store.chunks(datasetId).stream()
                .map(c -> new VectorDocument(c.id(), c.documentId(), c.datasetId(), c.content(),
                        embed.embed(datasetId, c.content()), c.metadata()))
                .toList();
        vectors.upsert(docs);
        answers.invalidateDataset(datasetId);
        return docs.size();
    }

    public int rebuildDataset(long datasetId) {
        List<Chunk> oldChunks = store.chunks(datasetId);
        oldChunks.forEach(c -> outbox.append("DELETE_CHUNK", datasetId, c.documentId(), c.id(), "{}"));
        List<Document> documents = store.documents(datasetId);
        documents.forEach(d -> vectors.deleteDocument(d.id()));
        answers.invalidateDataset(datasetId);
        documents.forEach(d -> index(d.id()));
        return documents.size();
    }

    public void index(long id) {
        try {
            Document document = store.document(id);
            Dataset dataset = store.dataset(document.datasetId());
            status(id, "PARSING", null, 0);
            status(id, "CHUNKING", null, 0);
            List<String> parts = splitter.split(document.content(), dataset.chunkSize(), dataset.chunkOverlap());
            List<Chunk> chunks = new ArrayList<>();
            List<VectorDocument> docs = new ArrayList<>();
            List<PendingOutbox> pendingOutbox = new ArrayList<>();
            Map<String, String> documentMetadata = metadata(document);
            Long parentId = null;
            String currentTitlePath = document.fileName();
            for (int i = 0; i < parts.size(); i++) {
                long chunkId = store.nextId();
                String detectedTitle = titleIn(parts.get(i));
                if (detectedTitle != null && !detectedTitle.equals(currentTitlePath)) {
                    currentTitlePath = detectedTitle;
                    parentId = null;
                }
                if (parentId == null) parentId = chunkId;
                Map<String, String> chunkMetadata = new LinkedHashMap<>(documentMetadata);
                chunkMetadata.put("titlePath", currentTitlePath);
                Chunk chunk = new Chunk(chunkId, document.id(), document.datasetId(), i, parts.get(i), chunkMetadata, parentId);
                chunks.add(chunk);
                docs.add(new VectorDocument(chunk.id(), chunk.documentId(), chunk.datasetId(), chunk.content(),
                        embed.embed(chunk.datasetId(), chunk.content()), chunk.metadata()));
                pendingOutbox.add(new PendingOutbox(chunk.datasetId(), chunk.documentId(), chunk.id(), json(chunk, document)));
            }
            status(id, "INDEXING", null, 0);
            store.chunks(id, chunks);
            vectors.upsert(docs);
            answers.invalidateDataset(document.datasetId());
            status(id, "READY", null, chunks.size());
            pendingOutbox.forEach(event -> outbox.append("UPSERT_CHUNK", event.datasetId(), event.documentId(),
                    event.chunkId(), event.payload()));
            events.publishEvent(new IndexingCompletedEvent(this, id, true, null));
        } catch (Exception error) {
            try {
                status(id, "FAILED", error.getMessage(), 0);
            } catch (Exception ignored) {
            }
            events.publishEvent(new IndexingCompletedEvent(this, id, false, error.getMessage()));
        }
    }

    private record PendingOutbox(long datasetId, long documentId, long chunkId, String payload) {
    }

    private void status(long id, String value, String error, int chunks) {
        store.status(id, value, error, chunks);
        sse.publish("document:" + id, new SseEvent(value, error == null ? value : error,
                Map.of("documentId", id, "status", value, "chunkCount", chunks)));
    }

    private Map<String, String> metadata(Document document) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("parser", parser(document.fileType()));
        values.put("charset", charset(document.fileType()));
        values.put("titlePath", document.fileName());
        values.put("version", "1");
        values.put("indexType", "default");
        values.put("titleCount", String.valueOf(titleCount(document.content())));
        values.put("paragraphCount", String.valueOf(paragraphCount(document.content())));
        values.put("charCount", String.valueOf(document.content() == null ? 0 : document.content().length()));
        values.put("tokenEstimate", String.valueOf(Math.max(1,
                (document.content() == null ? 0 : document.content().length()) / 4)));
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

    private int titleCount(String content) {
        if (content == null || content.isBlank()) return 0;
        int count = 0;
        for (String line : content.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("#") || value.matches("[一二三四五六七八九十0-9]+[、.．].{2,80}")) count++;
        }
        return count;
    }

    private int paragraphCount(String content) {
        if (content == null || content.isBlank()) return 0;
        int count = 0;
        for (String part : content.split("\\R\\s*\\R|\\R")) if (!part.trim().isBlank()) count++;
        return count;
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
