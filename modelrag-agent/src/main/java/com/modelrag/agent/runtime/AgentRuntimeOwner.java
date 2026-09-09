package com.modelrag.agent.runtime;

import java.net.InetAddress;
import java.util.UUID;

/** Stable owner identity for one JVM runtime instance. */
public final class AgentRuntimeOwner {
    private final String value;

    public AgentRuntimeOwner() {
        this(defaultOwner());
    }

    public AgentRuntimeOwner(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("runtime owner is invalid");
        }
        this.value = value;
    }

    public String value() { return value; }

    private static String defaultOwner() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + ProcessHandle.current().pid()
                    + ":" + UUID.randomUUID();
        } catch (Exception ignored) {
            return "modelrag:" + UUID.randomUUID();
        }
    }
}
