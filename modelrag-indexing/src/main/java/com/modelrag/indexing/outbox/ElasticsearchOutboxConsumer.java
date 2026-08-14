package com.modelrag.indexing.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.outbox.IndexOutbox;
import com.modelrag.common.exception.SafeErrorSummary;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.knowledge.service.ObjectStorageService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers the transactional outbox to Elasticsearch; failures stay pending with exponential backoff. */
@Component
@Profile("!test")
public class ElasticsearchOutboxConsumer {
    private final IndexOutbox outbox;
    private final KnowledgeStore knowledge;
    private final ObjectStorageService objectStorage;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String endpoint;
    private final long retiredIndexRetentionMillis;
    private final Set<String> preparedIndexes = ConcurrentHashMap.newKeySet();

    public ElasticsearchOutboxConsumer(IndexOutbox outbox, KnowledgeStore knowledge, ObjectStorageService objectStorage,
            @Value("${modelrag.elasticsearch.endpoint:http://localhost:9200}") String endpoint,
            @Value("${modelrag.elasticsearch.retired-index-retention-hours:24}") long retentionHours) {
        this.outbox = outbox;
        this.knowledge = knowledge;
        this.objectStorage = objectStorage;
        this.endpoint = endpoint.replaceAll("/$", "");
        this.retiredIndexRetentionMillis = Math.max(1, retentionHours) * 3_600_000L;
    }

    @Scheduled(fixedDelayString = "${modelrag.outbox.poll-ms:1000}")
    public void deliverDueEvents() {
        for (var event : outbox.due()) {
            outbox.save(event.processing());
            try {
                boolean documentDeletion = "DELETE_DOCUMENT".equals(event.eventType());
                boolean deletion = documentDeletion || "DELETE_CHUNK".equals(event.eventType());
                HttpRequest request;
                if (deletion) {
                    String field = documentDeletion ? "documentId" : "chunkId";
                    long value = documentDeletion ? event.documentId() : event.chunkId();
                    String body = "{\"query\":{\"term\":{\"" + field + "\":" + value + "}}}";
                    request = HttpRequest.newBuilder(URI.create(endpoint + "/modelrag-chunks-active/_delete_by_query"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                } else {
                    String physicalIndex = physicalIndex(version(event.payload()));
                    ensureIndex(physicalIndex);
                    request = HttpRequest.newBuilder(URI.create(endpoint + "/" + physicalIndex + "/_doc/" + event.chunkId()))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(event.payload())).build();
                }
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status < 200 || status >= 300) throw new IllegalStateException("Elasticsearch 返回 HTTP " + status);
                if (deletion) {
                    switchActiveAlias(activePhysicalIndexes(event, null));
                    if (documentDeletion) deleteObjects(event.payload());
                    outbox.save(event.done());
                } else if (outbox instanceof PostgresIndexOutbox postgres) {
                    // The current UPSERT must count as complete before checking whether the
                    // document version can atomically become the active alias target. If the
                    // alias operation fails, the catch branch returns this event to PENDING.
                    outbox.save(event.done());
                    long version = version(event.payload());
                    if (postgres.isVersionComplete(event.documentId(), version)) {
                        switchActiveAlias(activePhysicalIndexes(event, version));
                        postgres.markReadyIfComplete(event.documentId());
                    }
                } else {
                    // Explicit fake implementations use this branch for the alias contract test.
                    switchActiveAlias(activePhysicalIndexes(event, version(event.payload())));
                    outbox.save(event.done());
                }
            } catch (Exception ex) { outbox.save(event.failed(SafeErrorSummary.of(ex))); }
        }
    }

    private void deleteObjects(String payload) throws Exception {
        JsonNode value = json.readTree(payload == null ? "{}" : payload);
        deleteObject(value.path("artifactObjectKey").asText(""));
        deleteObject(value.path("sourceObjectKey").asText(""));
    }

    private void deleteObject(String objectKey) throws Exception {
        if (objectKey != null && !objectKey.isBlank()) objectStorage.delete(objectKey);
    }

    /** Deletes only inactive physical indexes after the configured rollback window. */
    @Scheduled(cron = "${modelrag.elasticsearch.retired-index-cleanup-cron:0 20 3 * * *}")
    public void cleanupRetiredIndexes() {
        try {
            Set<String> active = currentAliasIndexes();
            HttpRequest list = HttpRequest.newBuilder(URI.create(endpoint
                    + "/_cat/indices/modelrag-chunks-v*?format=json&h=index,creation.date"))
                    .GET().build();
            HttpResponse<String> response = http.send(list, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) return;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("列出 ES 物理索引失败 HTTP " + response.statusCode());
            }
            long cutoff = System.currentTimeMillis() - retiredIndexRetentionMillis;
            JsonNode rows = json.readTree(response.body());
            if (!rows.isArray()) throw new IllegalStateException("ES 物理索引列表格式无效");
            for (JsonNode row : rows) {
                String index = row.path("index").asText("");
                long createdAt = row.path("creation.date").asLong(Long.MAX_VALUE);
                if (!index.matches("modelrag-chunks-v\\d+") || active.contains(index) || createdAt > cutoff) continue;
                if (currentAliasIndexes().contains(index)) continue;
                HttpRequest delete = HttpRequest.newBuilder(URI.create(endpoint + "/" + index))
                        .DELETE().build();
                int status = http.send(delete, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status != 404 && (status < 200 || status >= 300)) {
                    throw new IllegalStateException("删除 ES 退休索引失败 HTTP " + status);
                }
                preparedIndexes.remove(index);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ES 退休索引清理被中断", interrupted);
        } catch (Exception error) {
            throw new IllegalStateException("ES 退休索引延迟清理失败", error);
        }
    }

    private Set<String> currentAliasIndexes() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/_alias/modelrag-chunks-active"))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Set.of();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("读取 ES active alias 失败 HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        Set<String> indexes = new HashSet<>();
        root.fieldNames().forEachRemaining(indexes::add);
        return Set.copyOf(indexes);
    }

    private void ensureIndex(String physicalIndex) {
        if (preparedIndexes.contains(physicalIndex)) return;
        synchronized (this) {
            if (preparedIndexes.contains(physicalIndex)) return;
            try {
                HttpRequest head = HttpRequest.newBuilder(URI.create(endpoint + "/" + physicalIndex))
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
                              "content":{"type":"text","analyzer":"smartcn","fields":{"keyword":{"type":"keyword","ignore_above":2048}}},
                              "titlePath":{"type":"text","analyzer":"smartcn","fields":{"keyword":{"type":"keyword","ignore_above":512}}},
                              "documentName":{"type":"text","analyzer":"smartcn","fields":{"keyword":{"type":"keyword","ignore_above":512}}},
                              "metadata":{"type":"object","enabled":true}
                            }}}
                            """;
                    HttpRequest create = HttpRequest.newBuilder(URI.create(endpoint + "/" + physicalIndex))
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                    int created = http.send(create, HttpResponse.BodyHandlers.discarding()).statusCode();
                    if (created < 200 || created >= 300) throw new IllegalStateException("创建 ES 索引失败 HTTP " + created);
                }
                preparedIndexes.add(physicalIndex);
            } catch (Exception error) {
                throw new IllegalStateException("Elasticsearch 索引映射或 active alias 未就绪", error);
            }
        }
    }

    /** Atomically switches the read alias to every physical index with active documents. */
    private void switchActiveAlias(Set<String> physicalIndexes) throws Exception {
        HttpRequest head = HttpRequest.newBuilder(URI.create(endpoint + "/modelrag-chunks-active"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        int aliasExists = http.send(head, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (aliasExists != 200 && aliasExists != 404) {
            throw new IllegalStateException("检查 ES active alias 失败 HTTP " + aliasExists);
        }
        if (aliasExists == 404 && physicalIndexes.isEmpty()) return;
        StringBuilder actions = new StringBuilder("{\"actions\":[");
        boolean hasAction = false;
        if (aliasExists == 200) {
            actions.append("{\"remove\":{\"index\":\"modelrag-chunks-v*\",\"alias\":\"modelrag-chunks-active\"}}");
            hasAction = true;
        }
        for (String physicalIndex : physicalIndexes) {
            if (hasAction) actions.append(',');
            actions.append("{\"add\":{\"index\":\"").append(physicalIndex)
                    .append("\",\"alias\":\"modelrag-chunks-active\"}}");
            hasAction = true;
        }
        actions.append("]}");
        if (!hasAction) return;
        HttpRequest alias = HttpRequest.newBuilder(URI.create(endpoint + "/_aliases"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(actions.toString())).build();
        int status = http.send(alias, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("原子切换 ES active alias 失败 HTTP " + status);
    }

    private Set<String> activePhysicalIndexes(com.modelrag.common.outbox.IndexOutboxEvent event, Long completedVersion) {
        Map<Long, Long> active = new HashMap<>(knowledge.allActiveIndexVersions());
        if (completedVersion == null) active.remove(event.documentId());
        else active.put(event.documentId(), completedVersion);
        return new HashSet<>(active.values().stream().map(this::physicalIndex).toList());
    }

    private String physicalIndex(long version) {
        return "modelrag-chunks-v" + Math.max(1, version);
    }

    private long version(String payload) {
        if (payload == null) return 1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"version\\\"\\s*:\\s*(\\d+)").matcher(payload);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 1;
    }
}
