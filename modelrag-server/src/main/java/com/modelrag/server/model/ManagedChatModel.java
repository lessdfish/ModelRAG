package com.modelrag.server.model;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** A request-scoped Spring AI model with an explicit transport shutdown hook. */
final class ManagedChatModel implements ChatModel, AutoCloseable {
    private final ChatModel delegate;
    private final Runnable close;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    ManagedChatModel(ChatModel delegate, Runnable close) {
        this.delegate = delegate;
        this.close = close;
    }

    @Override public ChatResponse call(Prompt prompt) { return delegate.call(prompt); }
    @Override public Flux<ChatResponse> stream(Prompt prompt) { return delegate.stream(prompt); }
    @Override public ChatOptions getOptions() { return delegate.getOptions(); }
    @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) close.run();
    }
}
