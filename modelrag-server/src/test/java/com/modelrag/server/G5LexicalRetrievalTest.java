package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.search.channel.v2.ElasticsearchRetrievalUnitSearch;
import com.modelrag.search.channel.v2.LexicalSearchRequest;
import com.modelrag.search.dto.RetrievalCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class G5LexicalRetrievalTest {
    @Test
    void lexicalQueryUsesV2IndexAndPostgresActiveBuildValidation() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(body);
        RetrievalUnitRepository units = mock(RetrievalUnitRepository.class);
        when(units.findActiveByIds(eq(7L), any())).thenReturn(List.of(unit(101, 31)));
        try {
            ElasticsearchRetrievalUnitSearch search = search(server, units);
            List<RetrievalCandidate> result = search.search(new LexicalSearchRequest(7, "policy", List.of(31L), 5));

            assertEquals(List.of(101L), result.stream().map(RetrievalCandidate::retrievalUnitId).toList());
            assertTrue(body.get().contains("\"indexBuildId\""));
            assertTrue(body.get().contains("titlePath^4"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void staleProjectionHitsAreDroppedAndCountedInOneValidationBatch() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(body);
        RetrievalUnitRepository units = mock(RetrievalUnitRepository.class);
        when(units.findActiveByIds(eq(7L), any())).thenReturn(List.of());
        try {
            SimpleMeterRegistry metrics = new SimpleMeterRegistry();
            ElasticsearchRetrievalUnitSearch search = new ElasticsearchRetrievalUnitSearch(
                    "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper(), units, metrics,
                    java.net.http.HttpClient.newHttpClient());

            assertTrue(search.search(new LexicalSearchRequest(7, "policy", List.of(31L), 5)).isEmpty());
            assertEquals(1.0, metrics.get("modelrag.retrieval.v2.lexical.stale_candidates").counter().count());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void overflowRecallContinuesWithSearchAfterUntilActiveCandidateIsFound() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/modelrag-retrieval-units-v2/_search", exchange -> {
            int page = requests.incrementAndGet();
            StringBuilder hits = new StringBuilder();
            int count = page == 1 ? 50 : 1;
            for (int index = 0; index < count; index++) {
                if (index > 0) hits.append(',');
                long id = page == 1 ? 101 + index : 999;
                hits.append("{\"_score\":1.2,\"sort\":[1.2,").append(id).append("],\"_source\":{")
                        .append("\"retrievalUnitId\":").append(id)
                        .append(",\"datasetId\":7,\"nodeId\":19,\"documentId\":23,")
                        .append("\"documentVersionId\":29,\"indexBuildId\":31,\"unitType\":\"SECTION\",")
                        .append("\"titlePath\":\"Title\",\"content\":\"content\",\"metadata\":{}}}");
            }
            byte[] response = ("{\"hits\":{\"hits\":[" + hits + "]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        RetrievalUnitRepository units = mock(RetrievalUnitRepository.class);
        when(units.findActiveByIds(eq(7L), any())).thenReturn(List.of(), List.of(unit(999, 31)));
        try {
            List<RetrievalCandidate> result = search(server, units).searchActiveValidated(
                    new LexicalSearchRequest(7, "policy", List.of(), 1));
            assertEquals(List.of(999L), result.stream().map(RetrievalCandidate::retrievalUnitId).toList());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void overflowReportsTruncationOnlyAfterThePageBudgetIsExhausted() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/modelrag-retrieval-units-v2/_search", exchange -> {
            int page = requests.incrementAndGet();
            StringBuilder hits = new StringBuilder();
            for (int index = 0; index < 50; index++) {
                if (index > 0) hits.append(',');
                long id = page * 100L + index;
                hits.append("{\"_score\":1.2,\"sort\":[1.2,").append(id).append("],\"_source\":{")
                        .append("\"retrievalUnitId\":").append(id)
                        .append(",\"datasetId\":7,\"nodeId\":19,\"documentId\":23,")
                        .append("\"documentVersionId\":29,\"indexBuildId\":31,\"unitType\":\"SECTION\",")
                        .append("\"titlePath\":\"Title\",\"content\":\"content\",\"metadata\":{}}}");
            }
            byte[] response = ("{\"hits\":{\"hits\":[" + hits + "]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        RetrievalUnitRepository units = mock(RetrievalUnitRepository.class);
        when(units.findActiveByIds(eq(7L), any())).thenReturn(List.of());
        try {
            var result = search(server, units).searchActiveValidatedResult(
                    new LexicalSearchRequest(7, "policy", List.of(), 1));
            assertTrue(result.candidates().isEmpty());
            assertTrue(result.truncated());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(AtomicReference<String> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/modelrag-retrieval-units-v2/_search", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String payload = "{\"hits\":{\"hits\":[{\"_score\":1.2,\"_source\":{"
                    + "\"retrievalUnitId\":101,\"datasetId\":7,\"nodeId\":19,\"documentId\":23,"
                    + "\"documentVersionId\":29,\"indexBuildId\":31,\"unitType\":\"SECTION\","
                    + "\"titlePath\":\"Title\",\"content\":\"content\",\"metadata\":{}}}]}}";
            byte[] response = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        return server;
    }

    private ElasticsearchRetrievalUnitSearch search(HttpServer server, RetrievalUnitRepository units) {
        return new ElasticsearchRetrievalUnitSearch(
                "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper(), units,
                new SimpleMeterRegistry(), java.net.http.HttpClient.newHttpClient());
    }

    private RetrievalUnit unit(long id, long buildId) {
        return new RetrievalUnit(id, 7, 23, 29, 19, buildId, RetrievalUnitType.SECTION, 0,
                "Title", "content", "hash", 1, Map.of(), Instant.now());
    }
}
