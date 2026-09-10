package com.modelrag.common.observability;

import java.util.UUID;
import java.util.concurrent.Callable;

/** Thread-local correlation with explicit wrappers for asynchronous retrieval work. */
public final class TraceCorrelation {
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<RetrievalTraceContext> TRACE = new ThreadLocal<>();
    private static final ThreadLocal<RetrievalTraceSession> SESSION = new ThreadLocal<>();

    private TraceCorrelation() { }

    public static String currentRequestId() {
        String value = REQUEST_ID.get();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    public static RetrievalTraceContext current() { return TRACE.get(); }
    public static RetrievalTraceSession currentSession() { return SESSION.get(); }

    public static Scope bindRequest(String requestId) {
        String previous = REQUEST_ID.get();
        REQUEST_ID.set(requestId);
        return () -> restore(REQUEST_ID, previous);
    }

    public static Scope bind(RetrievalTraceContext context) {
        String previousRequest = REQUEST_ID.get();
        RetrievalTraceContext previousTrace = TRACE.get();
        REQUEST_ID.set(context == null ? previousRequest : context.requestId());
        if (context == null) TRACE.remove();
        else TRACE.set(context);
        return () -> {
            restore(REQUEST_ID, previousRequest);
            restore(TRACE, previousTrace);
        };
    }

    public static Scope bindSession(RetrievalTraceSession session) {
        RetrievalTraceSession previous = SESSION.get();
        if (session == null) SESSION.remove();
        else SESSION.set(session);
        return () -> restore(SESSION, previous);
    }

    public static Scope bind(RetrievalTraceContext context, RetrievalTraceSession session) {
        Scope contextScope = bind(context);
        Scope sessionScope = bindSession(session);
        return () -> {
            sessionScope.close();
            contextScope.close();
        };
    }

    public static Runnable wrap(RetrievalTraceContext context, Runnable action) {
        return () -> {
            try (Scope ignored = bind(context)) { action.run(); }
        };
    }

    public static <T> Callable<T> wrap(RetrievalTraceContext context, Callable<T> action) {
        return () -> {
            try (Scope ignored = bind(context)) { return action.call(); }
        };
    }

    public static <T> Callable<T> wrap(RetrievalTraceContext context, RetrievalTraceSession session,
            Callable<T> action) {
        return () -> {
            try (Scope ignored = bind(context, session)) { return action.call(); }
        };
    }

    public static Runnable wrap(RetrievalTraceContext context, RetrievalTraceSession session, Runnable action) {
        return () -> {
            try (Scope ignored = bind(context, session)) { action.run(); }
        };
    }

    public static <T> T call(RetrievalTraceContext context, Callable<T> action) {
        try {
            return wrap(context, action).call();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static <T> T call(RetrievalTraceContext context, RetrievalTraceSession session, Callable<T> action) {
        try {
            return wrap(context, session, action).call();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static <T> void restore(ThreadLocal<T> local, T value) {
        if (value == null) local.remove();
        else local.set(value);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
