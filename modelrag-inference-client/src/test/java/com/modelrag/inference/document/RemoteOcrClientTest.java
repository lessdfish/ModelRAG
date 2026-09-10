package com.modelrag.inference.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.inference.client.AiServiceClient;
import com.modelrag.inference.client.AiServiceException;
import com.modelrag.inference.client.AiServiceProperties;
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

class RemoteOcrClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @TempDir
    Path temporary;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void returnsBoundedTextConfidenceAndBoundingBox() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        server = server(exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            write(exchange, 200, Map.of("blocks", List.of(Map.of("text", "OCR text", "confidence", 0.8,
                    "bounding_box", Map.of("left", 0.1, "top", 0.2, "right", 0.9, "bottom", 0.4)))));
        });
        Path source = Files.writeString(temporary.resolve("page.png"), "image bytes", StandardCharsets.UTF_8);

        OcrResponse result = client().ocr(source, "image/png", List.of("eng", "chi"), 2, Duration.ofSeconds(1));

        assertEquals("OCR text", result.blocks().get(0).text());
        assertEquals(0.8, result.blocks().get(0).confidence());
        assertEquals(0.1, result.blocks().get(0).boundingBox().get("left"));
        assertEquals(2, Integer.parseInt(extractField(request.get(), "page_number")));
    }

    @Test
    void rejectsInvalidOcrConfidenceFromRemote() throws Exception {
        server = server(exchange -> write(exchange, 200,
                Map.of("blocks", List.of(Map.of("text", "bad", "confidence", 2.0)))));
        Path source = Files.writeString(temporary.resolve("page.png"), "image bytes", StandardCharsets.UTF_8);

        assertThrows(AiServiceException.class,
                () -> client().ocr(source, "image/png", List.of(), null, Duration.ofSeconds(1)));
    }

    private RemoteOcrClient client() {
        int port = server.getAddress().getPort();
        AiServiceProperties properties = new AiServiceProperties(true,
                "http://127.0.0.1:" + port, "test-token", 500, 2_000, 2,
                16L * 1024 * 1024, 50L * 1024 * 1024);
        return new RemoteOcrClient(new AiServiceClient(properties, json), json);
    }

    private HttpServer server(HttpHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/v1/ocr", handler::handle);
        value.start();
        return value;
    }

    private String extractField(String value, String field) {
        int index = value.indexOf("name=\"" + field + "\"");
        int start = value.indexOf("\r\n\r\n", index) + 4;
        return value.substring(start, value.indexOf("\r\n", start));
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
