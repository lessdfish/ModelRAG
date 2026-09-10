package com.modelrag.inference.rerank;

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
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoteRerankProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void preservesStableCandidateIdsAndRequiresConfiguredModel() throws Exception {
        server = server(exchange -> write(exchange, 200,
                new RerankResponse(List.of(
                        new RerankScore("1", 0.2), new RerankScore("0", 0.9)))));

        List<RerankScore> scores = provider().rerank("cross-encoder-v1", "query",
                List.of(new RerankDocument("0", "first"), new RerankDocument("1", "second")),
                Duration.ofSeconds(1));

        assertEquals(List.of("1", "0"), scores.stream().map(RerankScore::id).toList());
        assertEquals(0.9, scores.get(1).score());
        assertThrows(AiServiceException.class, () -> provider().rerank("other", "query",
                List.of(new RerankDocument("0", "first")), Duration.ofSeconds(1)));
    }

    @Test
    void rejectsDuplicateUnknownAndNonFiniteScores() throws Exception {
        server = server(exchange -> write(exchange, 200,
                new RerankResponse(List.of(
                        new RerankScore("0", Double.NaN), new RerankScore("0", 0.3)))));

        assertThrows(AiServiceException.class, () -> provider().rerank("cross-encoder-v1", "query",
                List.of(new RerankDocument("0", "first"), new RerankDocument("1", "second")),
                Duration.ofSeconds(1)));
    }

    @Test
    void rejectsMoreThanOneHundredCandidatesBeforeCallingRemote() {
        List<RerankDocument> documents = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> new RerankDocument(Integer.toString(index), "candidate"))
                .toList();

        assertThrows(AiServiceException.class,
                () -> provider().rerank("cross-encoder-v1", "query", documents, Duration.ofSeconds(1)));
    }

    private RemoteRerankProvider provider() {
        int port = server == null ? 18180 : server.getAddress().getPort();
        AiServiceProperties properties = new AiServiceProperties(true,
                "http://127.0.0.1:" + port, "test-token", 500, 2_000, 2,
                16L * 1024 * 1024, 50L * 1024 * 1024);
        return new RemoteRerankProvider(new AiServiceClient(properties, json));
    }

    private HttpServer server(HttpHandler handler) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/v1/rerank", handler::handle);
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
