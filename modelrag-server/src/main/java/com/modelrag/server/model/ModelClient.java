package com.modelrag.server.model;

import java.util.function.Consumer;

public interface ModelClient {
    String name();
    ModelType type();
    String execute(String input);

    default void stream(String input, Consumer<String> consumer) {
        consumer.accept(execute(input));
    }
}
