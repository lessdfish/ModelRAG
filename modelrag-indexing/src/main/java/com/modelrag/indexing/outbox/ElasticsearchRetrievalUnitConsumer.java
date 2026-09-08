package com.modelrag.indexing.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.SafeErrorSummary;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers V2 retrieval-unit projections to one shared Elasticsearch index; no V1 alias is touched. */
@Component
@Profile("!test")
public class ElasticsearchRetrievalUnitConsumer {
    private static final String INDEX = "modelrag-retrieval-units-v2";
    private final RetrievalProjectionOutbox outbox;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String endpoint;
    private final Set<String> preparedIndexes = ConcurrentHashMap.newKeySet();

    @Autowired
    public ElasticsearchRetrievalUnitConsumer(RetrievalProjectionOutbox outbox, ObjectMapper json,
            @Value("${modelrag.elasticsearch.endpoint:http://localhost:9200}") String endpoint) {
        this.outbox = outbox;
        this.json = json;
        this.endpoint = endpoint.replaceAll("/$", "");
    }

    @Scheduled(fixedDelayString = "${modelrag.v2-outbox.poll-ms:1000}")
    public void deliverDueEvents() {
        for (RetrievalProjectionOutboxEvent event : outbox.claimDue(100)) {
            try {
                ensureIndex();
                String documentId = event.indexBuildId() + ":" + event.retrievalUnitId();
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/" + INDEX + "/_doc/" + documentId))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(event.payload())).build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("V2 Elasticsearch 返回 HTTP " + status);
                }
                outbox.markDone(event.id());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                outbox.markFailed(event.id(), SafeErrorSummary.of(interrupted));
                return;
            } catch (Exception error) {
                outbox.markFailed(event.id(), SafeErrorSummary.of(error));
            }
        }
    }

    private void ensureIndex() throws Exception {
        if (preparedIndexes.contains(INDEX)) return;
        synchronized (this) {
            if (preparedIndexes.contains(INDEX)) return;
            HttpRequest head = HttpRequest.newBuilder(URI.create(endpoint + "/" + INDEX))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            int status = http.send(head, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 404) {
                HttpRequest create = HttpRequest.newBuilder(URI.create(endpoint + "/" + INDEX))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(mapping()))
                        .build();
                int created = http.send(create, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (created < 200 || created >= 300) {
                    throw new IllegalStateException("创建 V2 Elasticsearch 索引失败 HTTP " + created);
                }
            } else if (status < 200 || status >= 300) {
                throw new IllegalStateException("检查 V2 Elasticsearch 索引失败 HTTP " + status);
            }
            preparedIndexes.add(INDEX);
        }
    }

    private String mapping() throws Exception {
        java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("retrievalUnitId", java.util.Map.of("type", "long"));
        properties.put("datasetId", java.util.Map.of("type", "long"));
        properties.put("documentId", java.util.Map.of("type", "long"));
        properties.put("documentVersionId", java.util.Map.of("type", "long"));
        properties.put("nodeId", java.util.Map.of("type", "long"));
        properties.put("indexBuildId", java.util.Map.of("type", "long"));
        properties.put("unitType", java.util.Map.of("type", "keyword"));
        properties.put("ordinal", java.util.Map.of("type", "integer"));
        properties.put("titlePath", java.util.Map.of("type", "text"));
        properties.put("content", java.util.Map.of("type", "text"));
        properties.put("contentHash", java.util.Map.of("type", "keyword"));
        properties.put("tokenCount", java.util.Map.of("type", "integer"));
        properties.put("metadata", java.util.Map.of("type", "object", "enabled", true));
        return json.writeValueAsString(java.util.Map.of(
                "mappings", java.util.Map.of("properties", properties)));
    }
}
