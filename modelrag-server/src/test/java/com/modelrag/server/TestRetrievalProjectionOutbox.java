package com.modelrag.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.indexing.outbox.RetrievalProjectionOutbox;
import com.modelrag.indexing.outbox.RetrievalProjectionOutboxEvent;
import com.modelrag.knowledge.model.RetrievalUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory V2 outbox fixture with the production idempotency and retry contract. */
final class TestRetrievalProjectionOutbox implements RetrievalProjectionOutbox {
    private static final int MAX_CLAIM = 100;
    private static final int MAX_RETRIES = 5;
    private static final String EVENT_TYPE = "UPSERT_RETRIEVAL_UNIT_V2";

    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, RetrievalProjectionOutboxEvent> events = new LinkedHashMap<>();
    private final Map<String, Long> idsByKey = new LinkedHashMap<>();
    private long nextId;

    @Override
    public synchronized void appendBatch(long buildId, List<RetrievalUnit> units) {
        if (buildId <= 0) throw new BusinessException(ErrorCode.VALIDATION, "V2 构建 ID 无效");
        if (units == null || units.isEmpty()) return;
        for (RetrievalUnit unit : units) {
            if (unit == null || unit.indexBuildId() != buildId) {
                throw new BusinessException(ErrorCode.VALIDATION, "V2 检索单元不属于指定构建");
            }
            String key = idempotencyKey(buildId, unit.id());
            if (idsByKey.containsKey(key)) continue;
            long id = ++nextId;
            events.put(id, new RetrievalProjectionOutboxEvent(id, EVENT_TYPE, unit.datasetId(),
                    unit.documentId(), unit.documentVersionId(), buildId, unit.id(), payload(unit), "PENDING", 0,
                    Instant.now(), null, false, null, key));
            idsByKey.put(key, id);
        }
    }

    @Override
    public synchronized List<RetrievalProjectionOutboxEvent> claimDue(int limit) {
        int boundedLimit = Math.min(MAX_CLAIM, Math.max(0, limit));
        if (boundedLimit == 0) return List.of();
        Instant now = Instant.now();
        List<RetrievalProjectionOutboxEvent> claimed = new ArrayList<>();
        for (RetrievalProjectionOutboxEvent event : events.values().stream()
                .sorted(Comparator.comparingLong(RetrievalProjectionOutboxEvent::id)).toList()) {
            boolean due = ("PENDING".equals(event.status())
                    && (event.nextRetryAt() == null || !event.nextRetryAt().isAfter(now)))
                    || ("PROCESSING".equals(event.status()) && event.leaseUntil() != null
                            && event.leaseUntil().isBefore(now));
            if (!due) continue;
            RetrievalProjectionOutboxEvent next = event.processing(now.plusSeconds(60));
            events.put(event.id(), next);
            claimed.add(next);
            if (claimed.size() == boundedLimit) break;
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized void markDone(long id) {
        RetrievalProjectionOutboxEvent event = events.get(id);
        if (event != null) events.put(id, event.done());
    }

    @Override
    public synchronized void markFailed(long id, String safeError) {
        RetrievalProjectionOutboxEvent event = events.get(id);
        if (event != null) events.put(id, event.failed(safeError));
    }

    @Override
    public synchronized long countByBuildAndStatus(long buildId, String status) {
        if (buildId <= 0 || status == null || status.isBlank()) return 0;
        return events.values().stream().filter(event -> event.indexBuildId() == buildId)
                .filter(event -> status.equals(event.status())).filter(event -> !event.deadLetter()).count();
    }

    @Override
    public synchronized boolean hasTerminalFailure(long buildId) {
        return buildId > 0 && events.values().stream()
                .anyMatch(event -> event.indexBuildId() == buildId && event.deadLetter());
    }

    synchronized RetrievalProjectionOutboxEvent event(long id) { return events.get(id); }

    synchronized long size() { return events.size(); }

    synchronized void forceDue(long id) {
        RetrievalProjectionOutboxEvent event = events.get(id);
        if (event == null) return;
        events.put(id, new RetrievalProjectionOutboxEvent(event.id(), event.eventType(), event.datasetId(),
                event.documentId(), event.documentVersionId(), event.indexBuildId(), event.retrievalUnitId(),
                event.payload(), "PENDING", event.retryCount(), Instant.EPOCH, null, event.deadLetter(),
                event.error(), event.idempotencyKey()));
    }

    private String idempotencyKey(long buildId, long unitId) {
        return EVENT_TYPE + ":" + buildId + ":" + unitId;
    }

    private String payload(RetrievalUnit unit) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("retrievalUnitId", unit.id());
        value.put("datasetId", unit.datasetId());
        value.put("documentId", unit.documentId());
        value.put("documentVersionId", unit.documentVersionId());
        value.put("nodeId", unit.nodeId());
        value.put("indexBuildId", unit.indexBuildId());
        value.put("unitType", unit.unitType().name());
        value.put("ordinal", unit.ordinal());
        value.put("titlePath", unit.titlePath());
        value.put("content", unit.content());
        value.put("contentHash", unit.contentHash());
        value.put("tokenCount", unit.tokenCount());
        value.put("metadata", unit.metadata());
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new BusinessException(ErrorCode.VALIDATION, "V2 检索单元无法序列化");
        }
    }
}
