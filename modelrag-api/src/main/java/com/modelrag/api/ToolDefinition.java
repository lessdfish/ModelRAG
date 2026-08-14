package com.modelrag.api;

public record ToolDefinition(String name, String description, Risk risk, String jsonSchema, boolean idempotent) {
    public enum Risk { READ_ONLY, WRITE, EXTERNAL_SIDE_EFFECT }
}
