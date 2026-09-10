package com.modelrag.inference.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.inference.client.AiServiceClient;
import com.modelrag.inference.client.AiServiceException;
import com.modelrag.inference.client.AiServiceProperties;
import com.modelrag.knowledge.model.NodeType;
import com.modelrag.knowledge.parser.DocumentParseMetadata;
import com.modelrag.knowledge.parser.ParseLimits;
import com.modelrag.knowledge.parser.ParsedDocument;
import com.modelrag.knowledge.parser.ParsedNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteDocumentAiClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @TempDir
    Path temporary;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void streamsJavaOwnedTempFileAndReturnsValidatedStructure() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        server = server(exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            write(exchange, 200, document("remote.pdf"));
        });
        Path source = Files.writeString(temporary.resolve("source.bin"), "pdf bytes", StandardCharsets.UTF_8);

        ParsedDocument result = client().parse(source, "remote.pdf", new ParseLimits(10, 1_000), Duration.ofSeconds(1));

        assertEquals("remote.pdf", result.nodes().get(0).title());
        assertTrue(request.get().contains("logical_file_name"));
        assertTrue(request.get().contains("remote.pdf"));
        assertTrue(!request.get().contains("sourceObjectKey"));
    }

    @Test
    void rejectsRemoteStructureThatFailsJavaIntegrityValidation() throws Exception {
        server = server(exchange -> write(exchange, 200, Map.of(
                "metadata", Map.of("parserName", "remote", "parserVersion", "1"),
                "nodes", List.of(new ParsedNode("root", null, NodeType.SECTION, 0, 0, "bad", "", false, Map.of())),
                "edges", List.of())));
        Path source = Files.writeString(temporary.resolve("source.bin"), "pdf bytes", StandardCharsets.UTF_8);

        assertThrows(AiServiceException.class,
                () -> client().parse(source, "remote.pdf", new ParseLimits(10, 1_000), Duration.ofSeconds(1)));
    }

    private RemoteDocumentAiClient client() {
        int port = server.getAddress().getPort();
        AiServiceProperties properties = new AiServiceProperties(true,
                "http://127.0.0.1:" + port, "test-token", 500, 2_000, 2,
                16L * 1024 * 1024, 50L * 1024 * 1024);
        return new RemoteDocumentAiClient(new AiServiceClient(properties, json), json);
    }

    private ParsedDocument document(String title) {
        return new ParsedDocument(new DocumentParseMetadata("remote-document-ai", "1", "REMOTE", true, Map.of()),
                List.of(new ParsedNode("root", null, NodeType.DOCUMENT, 0, 0, title, "body", true, Map.of())),
                List.of());
    }

    private HttpServer server(HttpHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/v1/documents/parse", handler::handle);
        value.start();
        return value;
    }

    private void write(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = json.writeValueAsBytes(value);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @FunctionalInterface
    private interface HttpHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
