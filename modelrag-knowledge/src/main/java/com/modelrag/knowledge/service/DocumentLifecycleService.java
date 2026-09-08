package com.modelrag.knowledge.service;

import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentParseStatus;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.DocumentVersionRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Coordinates only database metadata for a logical document and its content version. */
@Service
public class DocumentLifecycleService {
    private final DatasetRepository datasets;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final TransactionOperations transactions;

    public DocumentLifecycleService(DatasetRepository datasets, DocumentRepository documents,
            DocumentVersionRepository versions, TransactionOperations transactions) {
        this.datasets = datasets;
        this.documents = documents;
        this.versions = versions;
        this.transactions = transactions;
    }

    public Document createInitialVersion(long datasetId, String name, String type, String sourceHash,
            String sourceObjectKey, String artifactObjectKey, String contentHash, String parserName,
            String parserVersion) {
        return transactions.execute(status -> {
            datasets.findById(datasetId);
            Document document = documents.create(datasetId, name, type, sourceHash, null,
                    sourceObjectKey, artifactObjectKey, contentHash);
            DocumentVersion version = versions.create(document.id(), sourceHash, sourceObjectKey,
                    artifactObjectKey, contentHash, parserName, parserVersion, DocumentParseStatus.READY,
                    Map.of("source", "document-upload"));
            documents.activateVersion(document.id(), version.id());
            datasets.bumpRevision(datasetId);
            return documents.findById(document.id());
        });
    }
}
