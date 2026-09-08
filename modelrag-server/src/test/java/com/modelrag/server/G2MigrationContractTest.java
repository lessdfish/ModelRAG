package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class G2MigrationContractTest {
    @Test
    void migrationsAreAdditiveAndKeepContentVersionIndependentFromLegacyIndexVersion() throws IOException {
        String v54 = resource("db/migration/V54__document_versions.sql");
        String v55 = resource("db/migration/V55__document_nodes.sql");
        String v56 = resource("db/migration/V56__node_edges.sql");
        assertTrue(v54.contains("version_no, source_hash") || v54.contains("version_no,source_hash"));
        assertTrue(v54.contains("d.id, 1") || v54.contains("d.id,1"));
        assertTrue(v54.contains("active_version_id"));
        assertFalse(v54.contains("kb_document.version"));
        assertTrue(v55.contains("uq_document_node_root_version"));
        assertTrue(v55.contains("uq_document_node_sibling_ordinal"));
        assertFalse(v55.contains("INSERT INTO kb_document_node"));
        assertTrue(v56.contains("uq_node_edge_from_to_type"));
        assertTrue(v56.contains("metadata JSONB"));
    }

    private String resource(String name) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IOException("missing resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
