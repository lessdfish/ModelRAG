package com.modelrag.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.vector.VectorStore;
import com.modelrag.knowledge.model.Document;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Soft deletion and the ES cleanup event commit together; object cleanup happens after ES delivery. */
@Service
public class DocumentDeletionService {
    private final KnowledgeStore store;
    private final IndexOutbox outbox;
    private final TransactionOperations transactions;
    private final ObjectMapper json;
    private final VectorStore vectors;

    public DocumentDeletionService(KnowledgeStore store, IndexOutbox outbox, TransactionOperations transactions,
            ObjectMapper json, VectorStore vectors) {
        this.store = store;
        this.outbox = outbox;
        this.transactions = transactions;
        this.json = json;
        this.vectors = vectors;
    }

    public void deleteDocument(long datasetId, long documentId) {
        transactions.executeWithoutResult(status -> {
            Document document = store.document(documentId);
            if (document.datasetId() != datasetId) throw new IllegalArgumentException("文档不属于该知识库");
            appendDeletion(document);
            store.deleteDocument(datasetId, documentId);
            vectors.deleteDocument(documentId);
        });
    }

    public void deleteDataset(long datasetId) {
        transactions.executeWithoutResult(status -> {
            List<Document> documents = store.documents(datasetId);
            documents.forEach(this::appendDeletion);
            store.deleteDataset(datasetId);
            documents.forEach(document -> vectors.deleteDocument(document.id()));
        });
    }

    private void appendDeletion(Document document) {
        try {
            String payload = json.writeValueAsString(Map.of(
                    "sourceObjectKey", value(document.sourceObjectKey()),
                    "artifactObjectKey", value(document.artifactObjectKey())));
            outbox.append("DELETE_DOCUMENT", document.datasetId(), document.id(), 0, payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("文档删除事件无法序列化", error);
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
