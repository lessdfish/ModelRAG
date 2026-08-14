package com.modelrag.api;

public interface ChatModelProviderFactory {
    ModelClient create(String userId, String configId);

    interface ModelClient {
        String generate(String prompt);
        java.util.Set<Capability> capabilities();
    }

    enum Capability { STREAM, TOOL_CALLING, JSON_SCHEMA, VISION, EMBEDDING, RERANK }
}
