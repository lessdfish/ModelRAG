package com.modelrag.api;

public interface LongTermMemoryStore {
    java.util.List<Memory> find(String userId, Long datasetId, String query, int limit);
    Memory save(Memory memory);
    void delete(String userId, String memoryId);

    record Memory(String id, String userId, Long datasetId, String scope, String type, String memoryKey,
            String content, String status, java.time.Instant expiresAt) { }
}
