package com.modelrag.api;

import java.util.function.Consumer;

/** User-selected model execution boundary. No platform credential is implied by this contract. */
public interface UserModelProvider {
    String generate(String userId, String prompt);

    default void stream(String userId, String prompt, Consumer<String> consumer) {
        consumer.accept(generate(userId, prompt));
    }

    default boolean configured(String userId) { return false; }

    default String selectedModel(String userId) { return "user-configured"; }
}
