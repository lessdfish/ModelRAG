package com.modelrag.agent.runtime;

/** Why a durable execution was resumed. */
public enum ResumeReason {
    PROCESS_RECOVERY,
    APPROVAL_APPROVED,
    APPROVAL_REJECTED,
    CLIENT_RETRY
}
