package com.modelrag.common.model;

import java.util.function.Consumer;

/** Stable cross-module entry point for text generation. */
public interface ModelGateway {
    String generate(String prompt);

    /**
     * Streams provider chunks when the selected model supports it. The default
     * keeps non-streaming adapters compatible without hiding the capability.
     */
    default void stream(String prompt, Consumer<String> consumer) {
        consumer.accept(generate(prompt));
    }
}
