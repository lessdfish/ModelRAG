package com.modelrag.server.model;

import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local deterministic fallback used when a higher-priority model client is unavailable. */
@Component @Profile("test") @Order(100)
public class MockFallbackModelClient implements ModelClient {
    public String name(){return "mock-chat-fallback";}
    public ModelType type(){return ModelType.CHAT;}
    public String execute(String input){return "[fallback] "+input;}
}
