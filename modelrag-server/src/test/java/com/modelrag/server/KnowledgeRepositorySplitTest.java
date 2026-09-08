package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.vector.VectorStore;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Dataset;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.DatasetRepository;
import com.modelrag.knowledge.repository.DocumentRepository;
import com.modelrag.knowledge.repository.jdbc.JdbcChunkRepository;
import com.modelrag.knowledge.repository.jdbc.JdbcDatasetRepository;
import com.modelrag.knowledge.repository.jdbc.JdbcDocumentRepository;
import com.modelrag.knowledge.repository.jdbc.JdbcIndexVersionRepository;
import com.modelrag.knowledge.service.DocumentDeletionService;
import com.modelrag.knowledge.service.InMemoryKnowledgeStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

class KnowledgeRepositorySplitTest {
    @Test
    void testAdaptersExposeTheFourNarrowAggregateContracts() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
        DatasetRepository datasets = new TestDatasetRepository(store);
        DocumentRepository documents = new TestDocumentRepository(store);
        ChunkRepository chunks = new TestChunkRepository(store);

        Dataset dataset = datasets.create("contract", "repository", 512, 64);
        assertEquals(dataset, datasets.findById(dataset.id()));

        Document document = documents.create(dataset.id(), "fixture.md", "MD", "hash", "content");
        assertEquals(List.of(document), documents.findByDatasetId(dataset.id()));
        documents.updateStatus(document.id(), "READY", null, 1);
        assertEquals("READY", documents.findById(document.id()).status());

        Chunk chunk = new Chunk(chunks.nextId(), document.id(), dataset.id(), 0, "content",
                Map.of("version", "1"), null);
        chunks.replaceDocumentVersion(document.id(), List.of(chunk));
        assertEquals(Set.of(dataset.id()), datasets.findIndexedDatasetIds());
        assertEquals(List.of(chunk), chunks.findActiveByIds(dataset.id(), List.of(chunk.id())));
        assertEquals(List.of(dataset), datasets.route("contract", Set.of(dataset.id()), 3));

        assertEquals(dataset.revision() + 1, datasets.bumpRevision(dataset.id()).revision());
        documents.softDelete(document.id());
        assertTrue(chunks.findActiveByIds(dataset.id(), List.of(chunk.id())).contains(chunk),
                "document soft delete must not mutate legacy chunks");
    }

    @Test
    void jdbcDatasetRepositoryKeepsIndexedLookupAndRevisionBehavior() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Dataset expected = new Dataset(7, "dataset", "description", 600, 80, 5, .7, 3);
        doReturn(List.of(expected)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.queryForList(anyString(), eq(Long.class))).thenReturn(List.of(7L));

        JdbcDatasetRepository repository = new JdbcDatasetRepository(jdbc, (ignored, text) -> new float[] { 1 });

        assertEquals(expected, repository.findById(7));
        assertEquals(Set.of(7L), repository.findIndexedDatasetIds());
        assertEquals(expected, repository.bumpRevision(7));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("revision=revision+1"));
    }

    @Test
    void jdbcDocumentSoftDeleteDoesNotMutateChunks() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Document document = new Document(9, 7, "fixture.md", "MD", "hash", null,
                "READY", null, 1, "source", "artifact", "normalized");
        doReturn(List.of(document)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        new JdbcDocumentRepository(jdbc).softDelete(document.id());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getValue().contains("kb_document"));
        assertFalse(sql.getValue().contains("kb_chunk"));
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("kb_chunk"), any(Object[].class));
    }

    @Test
    void jdbcChunkAndIndexRepositoriesRetainTableAndVersionBoundaries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(9L))).thenReturn(4L);
        JdbcChunkRepository chunks = new JdbcChunkRepository(jdbc, new ObjectMapper());
        JdbcIndexVersionRepository versions = new JdbcIndexVersionRepository(jdbc);

        chunks.softDeleteByDocumentId(9);
        assertEquals(4L, versions.begin(9));
        versions.activate(9, 4);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("UPDATE kb_chunk")));
        assertTrue(sql.getAllValues().stream().anyMatch(value -> value.contains("active_index_version")));
        assertFalse(sql.getAllValues().stream().anyMatch(value -> value.contains("active_version_id")));
    }

    @Test
    void documentDeletionCoordinatesCrossRepositoryMutationInsideTransaction() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        IndexOutbox outbox = mock(IndexOutbox.class);
        VectorStore vectors = mock(VectorStore.class);
        TransactionOperations transactions = immediateTransactions();
        Document document = new Document(9, 7, "fixture.md", "MD", "hash", null,
                "READY", null, 1, "source", "artifact", "normalized");
        when(documents.findById(document.id())).thenReturn(document);

        DocumentDeletionService service = new DocumentDeletionService(datasets, documents, chunks, outbox,
                transactions, new ObjectMapper(), vectors);
        service.deleteDocument(7, document.id());

        verify(outbox).append(eq("DELETE_DOCUMENT"), eq(7L), eq(document.id()), eq(0L),
                org.mockito.ArgumentMatchers.argThat(payload -> payload.contains("artifact")
                        && payload.contains("source")));
        verify(documents).softDelete(document.id());
        verify(chunks).softDeleteByDocumentId(document.id());
        verify(datasets).bumpRevision(7);
        verify(vectors).deleteDocument(document.id());
    }

    @Test
    void datasetDeletionCoordinatesDocumentsChunksOutboxAndVectors() {
        DatasetRepository datasets = mock(DatasetRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        ChunkRepository chunks = mock(ChunkRepository.class);
        IndexOutbox outbox = mock(IndexOutbox.class);
        VectorStore vectors = mock(VectorStore.class);
        TransactionOperations transactions = immediateTransactions();
        Dataset dataset = new Dataset(7, "dataset", "description", 600, 80, 5, .7, 3);
        Document first = new Document(9, 7, "one.md", "MD", "one", null,
                "READY", null, 1, "source-one", "artifact-one", "normalized-one");
        Document second = new Document(10, 7, "two.md", "MD", "two", null,
                "READY", null, 1, "source-two", "artifact-two", "normalized-two");
        when(datasets.findById(dataset.id())).thenReturn(dataset);
        when(documents.findByDatasetId(dataset.id())).thenReturn(List.of(first, second));

        DocumentDeletionService service = new DocumentDeletionService(datasets, documents, chunks, outbox,
                transactions, new ObjectMapper(), vectors);
        service.deleteDataset(dataset.id());

        verify(outbox, org.mockito.Mockito.times(2)).append(eq("DELETE_DOCUMENT"), eq(7L), any(Long.class),
                eq(0L), anyString());
        verify(datasets).softDelete(dataset.id());
        verify(documents).softDeleteByDatasetId(dataset.id());
        verify(chunks).softDeleteByDatasetId(dataset.id());
        verify(vectors).deleteDocument(first.id());
        verify(vectors).deleteDocument(second.id());
    }

    private TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
