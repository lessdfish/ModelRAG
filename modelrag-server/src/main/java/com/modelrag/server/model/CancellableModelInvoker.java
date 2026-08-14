package com.modelrag.server.model;

import com.modelrag.api.ModelInvocationCanceller;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

/** Owns reactive Spring AI subscriptions so Agent cancellation can dispose in-flight HTTP calls. */
@Component
public class CancellableModelInvoker implements ModelInvocationCanceller {
    /** Keep interactive RAG responsive; evidence-backed local fallback handles a slow BYOK provider. */
    private static final Duration HARD_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_ACTIVE_INVOCATIONS = 256;
    private final ConcurrentHashMap<Thread, Disposable> active = new ConcurrentHashMap<>();

    public String invoke(ChatModel model, String prompt) {
        StringBuilder output = new StringBuilder();
        stream(model, prompt, output::append);
        return output.toString();
    }

    public void stream(ChatModel model, String prompt, Consumer<String> consumer) {
        Thread owner = Thread.currentThread();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        AtomicReference<Thread> providerWorker = new AtomicReference<>();
        Disposable tracked = () -> {
            cancelled.set(true);
            Disposable current = subscription.get();
            if (current != null) current.dispose();
            Thread worker = providerWorker.get();
            if (worker != null) worker.interrupt();
            finished.countDown();
            Thread.startVirtualThread(() -> close(model));
        };
        if (active.size() >= MAX_ACTIVE_INVOCATIONS || active.putIfAbsent(owner, tracked) != null) {
            tracked.dispose();
            throw new IllegalStateException("活动模型调用数已达上限或当前线程已有调用");
        }
        Thread worker = Thread.startVirtualThread(() -> {
            providerWorker.set(Thread.currentThread());
            try {
                Disposable created = model.stream(new Prompt(prompt == null ? "" : prompt)).subscribe(response -> {
                    String value = text(response);
                    if (!value.isBlank()) consumer.accept(value);
                }, error -> {
                    failure.set(error);
                    finished.countDown();
                }, finished::countDown);
                subscription.set(created);
                if (cancelled.get()) created.dispose();
            } catch (Throwable error) {
                failure.set(error);
                finished.countDown();
            }
        });
        providerWorker.compareAndSet(null, worker);
        try {
            if (!finished.await(HARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                tracked.dispose();
                throw new IllegalStateException("用户模型调用超时");
            }
            if (cancelled.get()) throw new java.util.concurrent.CancellationException("用户模型调用已取消");
            if (failure.get() != null) throw propagate(failure.get());
        } catch (InterruptedException interrupted) {
            tracked.dispose();
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("用户模型调用已取消");
        } finally {
            active.remove(owner, tracked);
            Disposable created = subscription.get();
            if (created != null) created.dispose();
            if (!worker.isAlive()) close(model);
        }
    }

    @Override
    public boolean cancel(Thread owner) {
        Disposable subscription = owner == null ? null : active.get(owner);
        if (subscription == null) return false;
        subscription.dispose();
        return true;
    }

    private String text(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                ? "" : response.getResult().getOutput().getText();
    }

    private RuntimeException propagate(Throwable error) {
        return error instanceof RuntimeException runtime ? runtime : new IllegalStateException("用户模型调用失败", error);
    }

    private void close(ChatModel model) {
        if (model instanceof AutoCloseable closeable) {
            try { closeable.close(); }
            catch (Exception ignored) { }
        }
    }
}
