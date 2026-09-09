package com.modelrag.agent.retrieval;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Typed envelope for one bounded retrieval action request. */
public record RetrievalActionRequest(RetrievalActionName action, Map<String, Object> arguments) {
    public RetrievalActionRequest {
        if (action == null) throw new IllegalArgumentException("retrieval action is required");
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public static RetrievalActionRequest of(RetrievalActionName action) {
        return new RetrievalActionRequest(action, Map.of());
    }

    public boolean has(String name) {
        return name != null && arguments.containsKey(name);
    }

    public String text(String name, int maxLength) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        if (text.length() > maxLength) throw new IllegalArgumentException(name + " is too long");
        return text.trim();
    }

    public int integer(String name, int defaultValue) {
        Object value = arguments.get(name);
        if (value == null) return defaultValue;
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        if (number.longValue() < Integer.MIN_VALUE || number.longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return number.intValue();
    }

    public long longInteger(String name) {
        Object value = arguments.get(name);
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long result = number.longValue();
        if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }
}
