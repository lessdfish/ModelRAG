package com.modelrag.common.outbox;

import java.util.List;

public interface IndexOutbox {
    IndexOutboxEvent append(String eventType, long datasetId, long documentId, long chunkId, String payload);

    List<IndexOutboxEvent> due();

    void save(IndexOutboxEvent event);

    int requeueDataset(long datasetId);
}
