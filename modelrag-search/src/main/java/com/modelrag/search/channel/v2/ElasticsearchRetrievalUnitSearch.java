package com.modelrag.search.channel.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.repository.RetrievalUnitRepository;
import com.modelrag.search.dto.RetrievalCandidate;
import com.modelrag.search.dto.RetrievalChannel;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** V2 Elasticsearch channel over the retrieval-unit projection only. */
@Service
@Profile("!test")
public class ElasticsearchRetrievalUnitSearch implements LexicalSearchPort {
    private static final String INDEX = "modelrag-retrieval-units-v2";

    private final String endpoint;
    private final ObjectMapper json;
    private final RetrievalUnitRepository units;
    private final MeterRegistry metrics;
    private final HttpClient http;

    @Autowired
    public ElasticsearchRetrievalUnitSearch(
            @Value("${modelrag.elasticsearch.endpoint:http://localhost:9200}") String endpoint,
            ObjectMapper json, RetrievalUnitRepository units, MeterRegistry metrics) {
        this(endpoint, json, units, metrics, HttpClient.newHttpClient());
    }

    public ElasticsearchRetrievalUnitSearch(String endpoint, ObjectMapper json, RetrievalUnitRepository units,
            MeterRegistry metrics, HttpClient http) {
        this.endpoint = endpoint.replaceAll("/$", "");
        this.json = json;
        this.units = units;
        this.metrics = metrics;
        this.http = http;
    }

    @Override
    public List<RetrievalCandidate> search(LexicalSearchRequest request) {
        if (request.activeIndexBuildIds().isEmpty()) return List.of();
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                    URI.create(endpoint + "/" + INDEX + "/_search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(query(request)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("V2 Elasticsearch 返回 HTTP " + response.statusCode());
            }
            JsonNode hits = json.readTree(response.body()).path("hits").path("hits");
            List<RetrievalCandidate> candidates = new ArrayList<>();
            for (JsonNode hit : hits) {
                RetrievalCandidate candidate = map(hit.path("_source"), hit.path("_score").asDouble(),
                        candidates.size() + 1);
                if (candidate != null) candidates.add(candidate);
            }
            return validateActive(request, candidates, hits.size());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("V2 Elasticsearch 词法检索被中断", interrupted);
        } catch (Exception error) {
            throw new IllegalStateException("V2 Elasticsearch 词法检索不可用", error);
        }
    }

    private String query(LexicalSearchRequest request) throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("size", request.limit());
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        term(filters, "datasetId", request.datasetId());
        ArrayNode builds = filters.addObject().putObject("terms").putArray("indexBuildId");
        request.activeIndexBuildIds().forEach(builds::add);

        ObjectNode multiMatch = bool.putArray("must").addObject().putObject("multi_match");
        multiMatch.put("query", request.query());
        multiMatch.put("type", "best_fields");
        multiMatch.put("operator", "or");
        ArrayNode fields = multiMatch.putArray("fields");
        fields.add("titlePath^4");
        fields.add("content");
        return json.writeValueAsString(root);
    }

    private List<RetrievalCandidate> validateActive(LexicalSearchRequest request,
            List<RetrievalCandidate> candidates, int rawHitCount) {
        if (candidates.isEmpty()) return List.of();
        Set<Long> ids = new LinkedHashSet<>(candidates.stream()
                .map(RetrievalCandidate::retrievalUnitId).toList());
        Map<Long, RetrievalUnit> active = new HashMap<>();
        for (RetrievalUnit unit : units.findActiveByIds(request.datasetId(), ids)) {
            active.put(unit.id(), unit);
        }
        List<RetrievalCandidate> retained = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            RetrievalUnit unit = active.get(candidate.retrievalUnitId());
            if (!request.activeIndexBuildIds().contains(candidate.indexBuildId())
                    || unit == null || unit.datasetId() != candidate.datasetId()
                    || unit.documentId() != candidate.documentId()
                    || unit.documentVersionId() != candidate.documentVersionId()
                    || unit.indexBuildId() != candidate.indexBuildId()) continue;
            retained.add(copy(candidate, retained.size() + 1));
        }
        int stale = Math.max(0, rawHitCount - retained.size());
        if (stale > 0) metrics.counter("modelrag.retrieval.v2.lexical.stale_candidates").increment(stale);
        return List.copyOf(retained);
    }

    private RetrievalCandidate map(JsonNode source, double score, int rank) {
        try {
            long unitId = source.path("retrievalUnitId").asLong(0);
            String unitType = source.path("unitType").asText("");
            if (unitId <= 0 || unitType.isBlank()) return null;
            return new RetrievalCandidate(source.path("datasetId").asLong(0), unitId,
                    source.path("nodeId").asLong(0), source.path("documentId").asLong(0),
                    source.path("documentVersionId").asLong(0), source.path("indexBuildId").asLong(0),
                    com.modelrag.knowledge.model.RetrievalUnitType.valueOf(unitType),
                    source.path("titlePath").asText(""), source.path("content").asText(""), score,
                    RetrievalChannel.LEXICAL, rank, metadata(source.path("metadata")));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Map<String, Object> metadata(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return Map.of();
        try {
            return json.convertValue(value, new TypeReference<Map<String, Object>>() { });
        } catch (IllegalArgumentException invalid) {
            return Map.of();
        }
    }

    private RetrievalCandidate copy(RetrievalCandidate candidate, int rank) {
        return new RetrievalCandidate(candidate.datasetId(), candidate.retrievalUnitId(), candidate.nodeId(),
                candidate.documentId(), candidate.documentVersionId(), candidate.indexBuildId(), candidate.unitType(),
                candidate.titlePath(), candidate.content(), candidate.score(), RetrievalChannel.LEXICAL, rank,
                candidate.metadata());
    }

    private void term(ArrayNode filters, String field, long value) {
        filters.addObject().putObject("term").put(field, value);
    }
}
