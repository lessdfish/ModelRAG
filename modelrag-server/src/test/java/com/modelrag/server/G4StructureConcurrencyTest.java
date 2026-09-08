package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.knowledge.model.DocumentParseStatus;
import com.modelrag.knowledge.model.DocumentVersion;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.parser.DocumentParseMetadata;
import com.modelrag.knowledge.parser.ParsedDocument;
import com.modelrag.knowledge.parser.ParsedNode;
import com.modelrag.knowledge.repository.DocumentVersionRepository;
import com.modelrag.indexing.pipeline.stage.StructurePersistStage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

class G4StructureConcurrencyTest {
    @Test
    void versionLockMakesConcurrentStructurePersistenceCreateOneTree() throws Exception {
        LockingVersionRepository versions = new LockingVersionRepository();
        versions.add(new DocumentVersion(1, 9, 1, "source", "source", "artifact", "content", "parser", "1",
                DocumentParseStatus.READY, Map.of(), Instant.now(), Instant.now()));
        TestDocumentStructureRepository structures = new TestDocumentStructureRepository();
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                try {
                    return action.doInTransaction(new SimpleTransactionStatus());
                } finally {
                    versions.unlockAfterTransaction();
                }
            }
        };
        StructurePersistStage stage = new StructurePersistStage(structures, versions, transactions);
        ParsedDocument parsed = parsedDocument();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> persist(stage, parsed, ready, start));
            var second = executor.submit(() -> persist(stage, parsed, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            StructurePersistStage.StructureResult firstResult = first.get(5, TimeUnit.SECONDS);
            StructurePersistStage.StructureResult secondResult = second.get(5, TimeUnit.SECONDS);

            assertEquals(firstResult.rootId(), secondResult.rootId());
            assertEquals(1, List.of(firstResult, secondResult).stream()
                    .filter(StructurePersistStage.StructureResult::reused).count());
            assertEquals(2, structures.countByVersion(1));
            assertEquals(1, structures.findRootByVersion(1).stream().count());
            assertFalse(firstResult.nodeIds().isEmpty() && secondResult.nodeIds().isEmpty());
        }
    }

    private StructurePersistStage.StructureResult persist(StructurePersistStage stage, ParsedDocument parsed,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return stage.persist(7, 9, 1, parsed);
    }

    private ParsedDocument parsedDocument() {
        return new ParsedDocument(new DocumentParseMetadata("test", "1", Map.of()), List.of(
                new ParsedNode("root", null, NodeType.DOCUMENT, 0, 0, "document.md", "", false, Map.of()),
                new ParsedNode("section", "root", NodeType.SECTION, 1, 0, "Section", "body", true, Map.of())),
                List.of());
    }

    private static final class LockingVersionRepository implements DocumentVersionRepository {
        private final TestDocumentVersionRepository delegate = new TestDocumentVersionRepository();
        private final ReentrantLock structureLock = new ReentrantLock();

        void add(DocumentVersion version) {
            delegate.create(version.documentId(), version.sourceHash(), version.sourceObjectKey(),
                    version.artifactObjectKey(), version.contentHash(), version.parserName(), version.parserVersion(),
                    version.parseStatus(), version.metadata());
        }

        @Override public DocumentVersion create(long documentId, String sourceHash, String sourceObjectKey,
                String artifactObjectKey, String contentHash, String parserName, String parserVersion,
                DocumentParseStatus parseStatus, Map<String, Object> metadata) {
            return delegate.create(documentId, sourceHash, sourceObjectKey, artifactObjectKey, contentHash,
                    parserName, parserVersion, parseStatus, metadata);
        }

        @Override public Optional<DocumentVersion> findById(long id) { return delegate.findById(id); }

        @Override public Optional<DocumentVersion> findActiveByDocumentId(long documentId) {
            return delegate.findActiveByDocumentId(documentId);
        }

        @Override public List<DocumentVersion> findByDocumentId(long documentId) {
            return delegate.findByDocumentId(documentId);
        }

        @Override public void lockForStructure(long documentVersionId) {
            if (findById(documentVersionId).isEmpty()) throw new IllegalArgumentException("版本不存在");
            structureLock.lock();
        }

        void unlockAfterTransaction() {
            if (structureLock.isHeldByCurrentThread()) structureLock.unlock();
        }
    }
}
