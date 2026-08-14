package com.modelrag.search.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Elasticsearch BM25 channel. Active document versions are applied as a server-side filter. */
@Service
@Profile("!test")
public class ElasticsearchBm25Search implements Bm25Search {
    private final String endpoint;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    public ElasticsearchBm25Search(
            @Value("${modelrag.elasticsearch.endpoint:http://localhost:9200}") String endpoint) {
        this.endpoint = endpoint.replaceAll("/$", "");
    }

    @Override
    public List<ScoredChunk> search(HybridSearchRequest request, int recall) {
        if (request.activeIndexVersions().isEmpty()) return List.of();
        try {
            ObjectNode root = json.createObjectNode();
            root.put("size", recall);
            ObjectNode bool = root.putObject("query").putObject("bool");
            ArrayNode filters = bool.putArray("filter");
            term(filters, "datasetId", request.datasetId());
            term(filters, "indexType", "default");

            ArrayNode versions = bool.putArray("should");
            request.activeIndexVersions().forEach((documentId, version) -> {
                ObjectNode clause = versions.addObject().putObject("bool");
                ArrayNode versionFilters = clause.putArray("filter");
                term(versionFilters, "documentId", documentId);
                term(versionFilters, "version", version);
            });
            bool.put("minimum_should_match", 1);

            ObjectNode multiMatch = bool.putArray("must").addObject().putObject("multi_match");
            multiMatch.put("query", request.query());
            multiMatch.put("type", "best_fields");
            ArrayNode fields = multiMatch.putArray("fields");
            fields.add("titlePath^4");
            fields.add("documentName^2");
            fields.add("content");
            multiMatch.put("operator", "or");

            HttpRequest call = HttpRequest.newBuilder(URI.create(endpoint + "/modelrag-chunks-active/_search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(root)))
                    .build();
            HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Elasticsearch 返回 HTTP " + response.statusCode());
            }
            JsonNode hits = json.readTree(response.body()).path("hits").path("hits");
            List<ScoredChunk> result = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                result.add(new ScoredChunk(source.path("chunkId").asLong(), source.path("content").asText(),
                        hit.path("_score").asDouble(), "bm25", result.size() + 1));
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Elasticsearch BM25 检索不可用", error);
        }
    }

    private void term(ArrayNode filters, String field, long value) {
        filters.addObject().putObject("term").put(field, value);
    }

    private void term(ArrayNode filters, String field, String value) {
        filters.addObject().putObject("term").put(field, value);
    }
}
