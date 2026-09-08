package com.modelrag.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.vector.VectorStore;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.knowledge.repository.DocumentRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Soft deletion and the ES cleanup event commit together; object cleanup happens after ES delivery. */
@Service
public class DocumentDeletionService {
    private final DatasetRepository datasets;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final IndexOutbox outbox;
    private final TransactionOperations transactions;
    private final ObjectMapper json;
    private final VectorStore vectors;

    public DocumentDeletionService(DatasetRepository datasets, DocumentRepository documents, ChunkRepository chunks,
            IndexOutbox outbox, TransactionOperations transactions,
            ObjectMapper json, VectorStore vectors) {
        this.datasets = datasets;
        this.documents = documents;
        this.chunks = chunks;
        this.outbox = outbox;
        this.transactions = transactions;
        this.json = json;
        this.vectors = vectors;
    }

    public void deleteDocument(long datasetId, long documentId) {
        transactions.executeWithoutResult(status -> {
            Document document = documents.findById(documentId);
            if (document.datasetId() != datasetId) throw new IllegalArgumentException("文档不属于该知识库");
            appendDeletion(document);
            documents.softDelete(documentId);
            chunks.softDeleteByDocumentId(documentId);
            datasets.bumpRevision(datasetId);
            vectors.deleteDocument(documentId);
        });
    }

    public void deleteDataset(long datasetId) {
        transactions.executeWithoutResult(status -> {
            datasets.findById(datasetId);
            List<Document> documentsForDataset = documents.findByDatasetId(datasetId);
            documentsForDataset.forEach(this::appendDeletion);
            datasets.softDelete(datasetId);
            documents.softDeleteByDatasetId(datasetId);
            chunks.softDeleteByDatasetId(datasetId);
            documentsForDataset.forEach(document -> vectors.deleteDocument(document.id()));
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
