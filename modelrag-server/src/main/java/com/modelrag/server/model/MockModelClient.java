package com.modelrag.server.model;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Deterministic model used only by the test profile. */
@Component
@Profile("test")
@Order(0)
public class MockModelClient implements ModelClient {
    public String name() { return "mock-chat"; }
    public ModelType type() { return ModelType.CHAT; }
    public String execute(String input) { return "[mock] " + input; }
}
