package com.modelrag.common.operation;

/** Guards side-effecting work that requires the shared short-lived coordination service. */
public interface OperationGuard {
    void requireAvailableForSideEffect();

    default void claimSideEffect(String idempotencyKey) {
        requireAvailableForSideEffect();
    }
}
