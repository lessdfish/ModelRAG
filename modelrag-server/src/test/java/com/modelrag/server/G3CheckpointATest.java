package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.knowledge.model.RetrievalUnitDraft;
import com.modelrag.knowledge.model.RetrievalUnitType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class G3CheckpointATest {
    @Test
    void oneNodeCanOwnMultipleProjectionUnitsAndActiveVisibilityUsesPointers() {
        TestRetrievalUnitRepository repository = repository();
        List<?> created = repository.createBatch(List.of(
                draft(RetrievalUnitType.PARAGRAPH, 0, Map.of("path", Map.of("chapter", "1"))),
                draft(RetrievalUnitType.SECTION_SUMMARY, 0, Map.of("path", Map.of("chapter", "1"))),
                draft(RetrievalUnitType.WINDOW, 1, Map.of("window", true))));

        assertEquals(3, created.size());
        assertEquals(1, repository.findByBuild(501, 0, 1).size());
        assertEquals(2, repository.findByNode(101, 1, 2).size());
        assertEquals(3, repository.findActiveByIds(7, List.of(1L, 2L, 3L)).size());
        assertEquals("1", ((Map<?, ?>) repository.findById(1).orElseThrow().metadata().get("path")).get("chapter"));
    }

    @Test
    void duplicateIdentityAndAggregateMismatchAreRejected() {
        TestRetrievalUnitRepository repository = repository();
        repository.createBatch(List.of(draft(RetrievalUnitType.PARAGRAPH, 0, Map.of())));
        assertThrows(BusinessException.class,
                () -> repository.createBatch(List.of(draft(RetrievalUnitType.PARAGRAPH, 0, Map.of()))));

        RetrievalUnitDraft mismatch = new RetrievalUnitDraft(7, 9, 999, 101, 501,
                RetrievalUnitType.WINDOW, 0, "", "text", "hash", 1, Map.of());
        assertThrows(BusinessException.class, () -> repository.createBatch(List.of(mismatch)));
    }

    @Test
    void inactivePointersHideUnitsWithoutPerUnitActiveState() {
        TestRetrievalUnitRepository repository = repository();
        repository.createBatch(List.of(draft(RetrievalUnitType.PARAGRAPH, 0, Map.of())));
        repository.activate(9, 4, 502);
        assertTrue(repository.findActiveByIds(7, List.of(1L)).isEmpty());
        assertFalse(repository.findById(1).orElseThrow().toString().contains("active"));
    }

    @Test
    void embeddingProfileIsUniqueAndIdentityComesFromTheRetrievalUnit() {
        TestRetrievalUnitRepository units = repository();
        units.createBatch(List.of(draft(RetrievalUnitType.PARAGRAPH, 0, Map.of())));
        units.setBuildState(501, "VECTOR_BUILDING");
        TestRetrievalEmbeddingRepository embeddings = new TestRetrievalEmbeddingRepository(units);
        embeddings.upsertBatch(501, "qwen3-v1", Map.of(1L, new float[1024]));
        embeddings.upsertBatch(501, "qwen3-v1", Map.of(1L, new float[1024]));

        assertEquals(1, embeddings.countByBuild(501));
        assertEquals(7, embeddings.row(1, "qwen3-v1").datasetId());
        assertEquals(9, embeddings.row(1, "qwen3-v1").documentId());
        assertThrows(BusinessException.class,
                () -> embeddings.upsertBatch(502, "qwen3-v1", Map.of(1L, new float[1024])));
    }

    @Test
    void migrationsDefineAdditiveV2ProjectionWithoutActiveUnitFlag() throws IOException {
        String v57 = resource("db/migration/V57__retrieval_units.sql");
        String v58 = resource("db/migration/V58__retrieval_unit_embeddings.sql");
        assertTrue(v57.contains("CREATE TABLE kb_retrieval_unit"));
        assertTrue(v57.contains("index_build_id BIGINT NOT NULL"));
        assertTrue(v57.contains("uq_retrieval_unit_build_node_type_ordinal"));
        assertFalse(v57.matches("(?is).*\\bactive\\s+(BOOLEAN|BOOL).*"));
        assertTrue(v58.contains("CREATE TABLE kb_vector_embedding"));
        assertTrue(v58.contains("embedding vector(1024) NOT NULL"));
        assertTrue(v58.contains("uq_vector_embedding_unit_profile"));
        assertTrue(v58.contains("USING hnsw"));
    }

    private TestRetrievalUnitRepository repository() {
        TestRetrievalUnitRepository repository = new TestRetrievalUnitRepository();
        repository.registerNode(101, 7, 9, 4);
        repository.registerBuild(501, 7, 9, 4, "UNIT_BUILDING");
        repository.registerBuild(502, 7, 9, 5, "UNIT_BUILDING");
        repository.activate(9, 4, 501);
        return repository;
    }

    private RetrievalUnitDraft draft(RetrievalUnitType type, int ordinal, Map<String, Object> metadata) {
        return new RetrievalUnitDraft(7, 9, 4, 101, 501, type, ordinal, "1", "text", "hash-" + type + ordinal,
                1, metadata);
    }

    private String resource(String name) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("missing resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
