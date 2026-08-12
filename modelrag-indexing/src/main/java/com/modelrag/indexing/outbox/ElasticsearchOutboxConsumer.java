package com.modelrag.indexing.outbox;

import com.modelrag.common.outbox.IndexOutbox;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers the transactional outbox to Elasticsearch; failures stay pending with exponential backoff. */
@Component
public class ElasticsearchOutboxConsumer {
    private final IndexOutbox outbox;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String endpoint;
    private volatile boolean indexChecked;

    public ElasticsearchOutboxConsumer(IndexOutbox outbox,
            @Value("${modelrag.elasticsearch.endpoint:http://localhost:9200}") String endpoint) {
        this.outbox = outbox;
        this.endpoint = endpoint.replaceAll("/$", "");
    }

    @Scheduled(fixedDelayString = "${modelrag.outbox.poll-ms:1000}")
    public void deliverDueEvents() {
        ensureIndex();
        for (var event : outbox.due()) {
            outbox.save(event.processing());
            try {
                boolean deletion = "DELETE_CHUNK".equals(event.eventType());
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + "/modelrag-chunks/_doc/" + event.chunkId()));
                HttpRequest request = deletion
                        ? builder.DELETE().build()
                        : builder.header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(event.payload())).build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if ((status < 200 || status >= 300) && !(deletion && status == 404)) throw new IllegalStateException("Elasticsearch 返回 HTTP " + status);
                outbox.save(event.done());
            } catch (Exception ex) { outbox.save(event.failed(ex.getMessage())); }
        }
    }

    private void ensureIndex() {
        if (indexChecked) return;
        synchronized (this) {
            if (indexChecked) return;
            try {
                HttpRequest head = HttpRequest.newBuilder(URI.create(endpoint + "/modelrag-chunks"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                int status = http.send(head, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 404) {
                    String body = """
                            {"mappings":{"properties":{
                              "chunkId":{"type":"long"},
                              "datasetId":{"type":"long"},
                              "documentId":{"type":"long"},
                              "parentChunkId":{"type":"long"},
                              "chunkIndex":{"type":"integer"},
                              "version":{"type":"integer"},
                              "indexType":{"type":"keyword"},
                              "titlePath":{"type":"text","fields":{"keyword":{"type":"keyword","ignore_above":512}}},
                              "documentName":{"type":"text","fields":{"keyword":{"type":"keyword","ignore_above":512}}},
                              "content":{"type":"text"},
                              "metadata":{"type":"object","enabled":true}
                            }}}
                            """;
                    HttpRequest create = HttpRequest.newBuilder(URI.create(endpoint + "/modelrag-chunks"))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                    int created = http.send(create, HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (created < 200 || created >= 300) throw new IllegalStateException("创建 ES 索引失败 HTTP " + created);
                }
                indexChecked = true;
            } catch (Exception ignored) {
                indexChecked = false;
            }
        }
    }
}
