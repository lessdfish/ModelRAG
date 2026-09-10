package com.modelrag.inference.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.inference.client.AiServiceClient;
import com.modelrag.inference.client.AiServiceException;
import com.modelrag.inference.client.AiServiceProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoteEmbeddingProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsCompatibleProfileDimensionsAuthAndRequestId() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(exchange.getRequestHeaders().getFirst("X-Request-Id") != null);
            calls.incrementAndGet();
            write(exchange, 200, new EmbeddingResponse(RemoteEmbeddingProvider.PROFILE,
                    RemoteEmbeddingProvider.DIMENSIONS, vectors(2)));
        });
        server.start();

        RemoteEmbeddingProvider provider = provider(16L * 1024 * 1024);
        List<float[]> result = provider.embed(RemoteEmbeddingProvider.PROFILE, 1024,
                List.of("员工年假制度", "请假审批流程"), Duration.ofSeconds(2));

        assertEquals(2, result.size());
        assertEquals(1024, result.get(0).length);
        assertEquals(1, calls.get());
    }

    @Test
    void retriesOneTransientTransportFailureWithinTheBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            if (calls.getAndIncrement() == 0) {
                write(exchange, 503, "{}");
            } else {
                write(exchange, 200, new EmbeddingResponse(RemoteEmbeddingProvider.PROFILE, 1024, vectors(1)));
            }
        });
        server.start();

        List<float[]> result = provider(16L * 1024 * 1024).embed(RemoteEmbeddingProvider.PROFILE, 1024,
                List.of("一次重试"), Duration.ofSeconds(2));

        assertEquals(1, result.size());
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsAlternateProfileDimensionsAndMalformedResponses() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> provider(16L * 1024 * 1024).embed("other", 1024, List.of("x"), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> provider(16L * 1024 * 1024).embed(RemoteEmbeddingProvider.PROFILE, 768, List.of("x"), Duration.ofSeconds(1)));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> write(exchange, 200,
                new EmbeddingResponse(RemoteEmbeddingProvider.PROFILE, 1024,
                        List.of(new float[RemoteEmbeddingProvider.DIMENSIONS - 1]))));
        server.start();
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> provider(16L * 1024 * 1024).embed(RemoteEmbeddingProvider.PROFILE, 1024,
                        List.of("bad response"), Duration.ofSeconds(1)));
        assertEquals(502, error.statusCode());
    }

    @Test
    void rejectsResponsesAboveTheConfiguredCap() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> write(exchange, 200,
                "x".repeat(2_000)));
        server.start();

        AiServiceException error = assertThrows(AiServiceException.class,
                () -> provider(128).embed(RemoteEmbeddingProvider.PROFILE, 1024,
                        List.of("too large"), Duration.ofSeconds(1)));
        assertEquals(413, error.statusCode());
    }

    private RemoteEmbeddingProvider provider(long responseCap) {
        int port = server == null ? 18180 : server.getAddress().getPort();
        AiServiceProperties properties = new AiServiceProperties(true,
                "http://127.0.0.1:" + port, "test-token", 500, 2_000, 2,
                responseCap, 50L * 1024 * 1024);
        return new RemoteEmbeddingProvider(new AiServiceClient(properties, new ObjectMapper()), properties);
    }

    private List<float[]> vectors(int count) {
        List<float[]> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            float[] vector = new float[RemoteEmbeddingProvider.DIMENSIONS];
            vector[index] = 1;
            result.add(vector);
        }
        return result;
    }

    private void write(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = value instanceof String text ? text.getBytes(StandardCharsets.UTF_8)
                : new ObjectMapper().writeValueAsBytes(value);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
