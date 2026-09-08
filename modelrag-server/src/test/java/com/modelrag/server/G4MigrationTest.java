package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class G4MigrationTest {
    @Test
    void v60DefinesAnAdditiveBoundedV2ProjectionOutbox() throws IOException {
        String migration = resource("db/migration/V60__retrieval_projection_outbox.sql");

        assertTrue(migration.contains("CREATE TABLE kb_retrieval_projection_outbox"));
        assertTrue(migration.contains("document_version_id BIGINT NOT NULL REFERENCES kb_document_version(id)"));
        assertTrue(migration.contains("index_build_id BIGINT NOT NULL REFERENCES kb_index_build(id)"));
        assertTrue(migration.contains("retrieval_unit_id BIGINT NOT NULL REFERENCES kb_retrieval_unit(id)"));
        assertTrue(migration.contains("idempotency_key VARCHAR(300) NOT NULL UNIQUE"));
        assertTrue(migration.contains("idx_retrieval_projection_outbox_due"));
        assertTrue(migration.contains("idx_retrieval_projection_outbox_build_status"));
        assertTrue(migration.contains("FOR UPDATE" ) == false);
        assertFalse(migration.matches("(?is).*\\bDROP\\s+(TABLE|COLUMN).*"));
    }

    @Test
    void historicalV1SchemaIsNotRewrittenByTheG4Migration() throws IOException {
        String migration = resource("db/migration/V60__retrieval_projection_outbox.sql");
        assertFalse(migration.contains("kb_chunk"));
        assertFalse(migration.contains("active_index_version"));
    }

    private String resource(String name) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("missing resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
