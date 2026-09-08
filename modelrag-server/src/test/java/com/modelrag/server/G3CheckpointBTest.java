package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.service.IndexBuildLifecycleService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

class G3CheckpointBTest {
    @Test
    void buildNumbersRemainSequentialUnderConcurrentCreation() {
        TestIndexBuildRepository builds = new TestIndexBuildRepository();
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById(9)).thenReturn(document(4, null));
        IndexBuildLifecycleService service = new IndexBuildLifecycleService(builds, documents, immediateTransactions());

        Set<Long> numbers = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 12).parallel().forEach(ignored -> numbers.add(
                service.createBuild(7, 9, 4, "qwen3-v1", null, Map.of()).buildNo()));

        assertEquals(12, numbers.size());
        assertEquals(12, numbers.stream().mapToLong(Long::longValue).max().orElse(0));
    }

    @Test
    void invalidTransitionsAndCompareAndSetAreRejected() {
        TestIndexBuildRepository builds = new TestIndexBuildRepository();
        IndexBuild build = builds.create(7, 9, 4, "qwen3-v1", null, Map.of());
        assertThrows(BusinessException.class,
                () -> builds.transition(build.id(), IndexBuildState.CREATED, IndexBuildState.ACTIVE));
        assertTrue(builds.transition(build.id(), IndexBuildState.CREATED, IndexBuildState.PARSING));
        assertTrue(builds.transition(build.id(), IndexBuildState.PARSING, IndexBuildState.STRUCTURE_READY));
        assertTrue(!builds.transition(build.id(), IndexBuildState.PARSING, IndexBuildState.STRUCTURE_READY));
    }

    @Test
    void readyActivationSupersedesPreviousBuildWithoutChangingLegacyAxis() {
        TestIndexBuildRepository builds = new TestIndexBuildRepository();
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById(9)).thenReturn(document(4, null));
        IndexBuildLifecycleService service = new IndexBuildLifecycleService(builds, documents, immediateTransactions());

        IndexBuild first = ready(service.createBuild(7, 9, 4, "qwen3-v1", null, Map.of()), service);
        service.activateBuild(first.id());
        IndexBuild second = ready(service.createBuild(7, 9, 4, "qwen3-v1", null, Map.of()), service);
        service.activateBuild(second.id());

        assertEquals(IndexBuildState.SUPERSEDED, builds.findById(first.id()).orElseThrow().state());
        assertEquals(IndexBuildState.ACTIVE, builds.findById(second.id()).orElseThrow().state());
        verify(documents).activateIndexBuild(9, first.id());
        verify(documents).activateIndexBuild(9, second.id());
    }

    @Test
    void failedOrStaleBuildCannotReplaceThePreviousActiveBuild() {
        TestIndexBuildRepository builds = new TestIndexBuildRepository();
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById(9)).thenReturn(document(4, null));
        IndexBuildLifecycleService service = new IndexBuildLifecycleService(builds, documents, immediateTransactions());

        IndexBuild active = ready(service.createBuild(7, 9, 4, "qwen3-v1", null, Map.of()), service);
        service.activateBuild(active.id());
        IndexBuild failed = ready(service.createBuild(7, 9, 4, "qwen3-v1", null, Map.of()), service);
        service.fail(failed.id(), "safe failure");
        assertEquals(active.id(), builds.findActiveByDocumentId(9).orElseThrow().id());

        when(documents.findById(9)).thenReturn(document(5, active.id()));
        IndexBuild stale = ready(service.createBuild(7, 9, 5, "qwen3-v1", null, Map.of()), service);
        // The target is deliberately made stale after creation by changing the active content version.
        when(documents.findById(9)).thenReturn(document(4, active.id()));
        assertThrows(BusinessException.class, () -> service.activateBuild(stale.id()));
        assertEquals(IndexBuildState.READY, builds.findById(stale.id()).orElseThrow().state());
        assertEquals(active.id(), builds.findActiveByDocumentId(9).orElseThrow().id());
    }

    @Test
    void v59AddsOnlyTheV2PointerAndDelayedForeignKeys() throws IOException {
        String v59 = resource("db/migration/V59__index_builds.sql");
        assertTrue(v59.contains("CREATE TABLE kb_index_build"));
        assertTrue(v59.contains("active_index_build_id"));
        assertTrue(v59.contains("fk_retrieval_unit_index_build"));
        assertTrue(v59.contains("fk_vector_embedding_index_build"));
        assertTrue(v59.contains("active_index_version") == false);
        assertTrue(v59.contains("kb_chunk") == false);
    }

    private IndexBuild ready(IndexBuild build, IndexBuildLifecycleService service) {
        service.transition(build.id(), IndexBuildState.CREATED, IndexBuildState.PARSING);
        service.transition(build.id(), IndexBuildState.PARSING, IndexBuildState.STRUCTURE_READY);
        service.transition(build.id(), IndexBuildState.STRUCTURE_READY, IndexBuildState.UNIT_BUILDING);
        service.transition(build.id(), IndexBuildState.UNIT_BUILDING, IndexBuildState.UNIT_READY);
        service.transition(build.id(), IndexBuildState.UNIT_READY, IndexBuildState.VECTOR_BUILDING);
        service.transition(build.id(), IndexBuildState.VECTOR_BUILDING, IndexBuildState.VECTOR_READY);
        service.transition(build.id(), IndexBuildState.VECTOR_READY, IndexBuildState.LEXICAL_SYNCING);
        service.transition(build.id(), IndexBuildState.LEXICAL_SYNCING, IndexBuildState.VERIFYING);
        return service.markReady(build.id());
    }

    private Document document(long activeVersion, Long activeBuild) {
        return new Document(9, 7, "doc.md", "MD", "hash", null, "READY", null, 0,
                "source", "artifact", "content", activeVersion, activeBuild);
    }

    private TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private String resource(String name) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("missing resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
