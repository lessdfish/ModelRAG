package com.modelrag.api;

/** Cancels an active provider invocation owned by an application execution thread. */
public interface ModelInvocationCanceller {
    boolean cancel(Thread owner);
}
