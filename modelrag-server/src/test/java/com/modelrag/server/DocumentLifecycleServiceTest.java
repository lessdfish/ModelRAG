package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.DocumentParseStatus;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.DocumentVersionRepository;
import com.modelrag.knowledge.service.DocumentLifecycleService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

class DocumentLifecycleServiceTest {
    @Test
    void contentVersionsUseIndependentSequentialNumbers() {
        TestDocumentVersionRepository versions = new TestDocumentVersionRepository();
        DocumentVersion first = versions.create(9, "source-1", "source-1", "artifact-1", "content-1",
                "markdown", null, DocumentParseStatus.READY, Map.of());
        DocumentVersion second = versions.create(9, "source-2", "source-2", "artifact-2", "content-2",
                "markdown", null, DocumentParseStatus.READY, Map.of());
        assertEquals(1, first.versionNo());
        assertEquals(2, second.versionNo());
    }

    @Test
    void createsAndActivatesContentVersionBeforeRevisionBump() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
        Document created = new Document(9, 7, "guide.md", "MD", "source-hash", null,
                "BUILDING", null, 0, "source-key", "artifact-key", "content-hash");
        DocumentVersion version = new DocumentVersion(19, 9, 1, "source-hash", "source-key", "artifact-key",
                "content-hash", "markdown", null, DocumentParseStatus.READY, Map.of("source", "document-upload"),
                Instant.now(), Instant.now());
        Document active = created.withActiveVersionId(version.id());
        when(documents.create(eq(7L), eq("guide.md"), eq("MD"), eq("source-hash"), eq(null),
                eq("source-key"), eq("artifact-key"), eq("content-hash"))).thenReturn(created);
        when(versions.create(eq(9L), eq("source-hash"), eq("source-key"), eq("artifact-key"),
                eq("content-hash"), eq("markdown"), eq(null), eq(DocumentParseStatus.READY), any(Map.class)))
                .thenReturn(version);
        when(documents.findById(9)).thenReturn(active);
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };

        Document result = new DocumentLifecycleService(datasets, documents, versions, transactions)
                .createInitialVersion(7, "guide.md", "MD", "source-hash", "source-key", "artifact-key",
                        "content-hash", "markdown", null);

        assertEquals(version.id(), result.activeVersionId());
        InOrder order = inOrder(datasets, documents, versions);
        order.verify(datasets).findById(7);
        order.verify(documents).create(eq(7L), eq("guide.md"), eq("MD"), eq("source-hash"), eq(null),
                eq("source-key"), eq("artifact-key"), eq("content-hash"));
        order.verify(versions).create(eq(9L), eq("source-hash"), eq("source-key"), eq("artifact-key"),
                eq("content-hash"), eq("markdown"), eq(null), eq(DocumentParseStatus.READY), any(Map.class));
        order.verify(documents).activateVersion(9, version.id());
        order.verify(datasets).bumpRevision(7);
        order.verify(documents).findById(9);
    }
}
