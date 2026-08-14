package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Test
    void v2DocumentIsCompleteAndInternallyConsistent() throws Exception {
        JsonNode root;
        try (InputStream input = getClass().getResourceAsStream("/openapi-v2.json")) {
            assertTrue(input != null, "OpenAPI resource is missing");
            root = new ObjectMapper().readTree(input);
        }
        assertEquals("3.1.0", root.path("openapi").asText());
        assertTrue(root.path("paths").size() >= 70, "unexpectedly incomplete v2 path surface");
        assertEquals("ModelRAG-HMAC", root.at("/components/securitySchemes/bearerAuth/bearerFormat").asText());

        Set<String> operationIds = new HashSet<>();
        root.path("paths").properties().forEach(path -> path.getValue().properties().stream()
                .filter(operation -> METHODS.contains(operation.getKey()))
                .forEach(operation -> {
                    String id = operation.getValue().path("operationId").asText();
                    assertFalse(id.isBlank(), path.getKey() + " " + operation.getKey() + " has no operationId");
                    assertTrue(operationIds.add(id), "duplicate operationId: " + id);
                }));

        String document = root.toString();
        java.util.regex.Matcher references = java.util.regex.Pattern
                .compile("#/components/schemas/([A-Za-z0-9_-]+)").matcher(document);
        while (references.find()) {
            assertTrue(root.at("/components/schemas/" + references.group(1)).isObject(),
                    "missing schema: " + references.group(1));
        }
    }
}
