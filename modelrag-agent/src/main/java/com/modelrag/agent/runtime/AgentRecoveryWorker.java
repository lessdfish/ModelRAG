package com.modelrag.agent.runtime;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded recovery scanner; discovery never implies ownership. */
@Component
@Profile("!test")
public class AgentRecoveryWorker {
    private final AgentRuntime runtime;
    private final int batchSize;

    public AgentRecoveryWorker(AgentRuntime runtime,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.runtime.recovery-batch-size:20}")
            int batchSize) {
        this.runtime = runtime;
        this.batchSize = Math.max(1, Math.min(100, batchSize));
    }

    @Scheduled(fixedDelayString = "${modelrag.agent.runtime.recovery-poll-ms:5000}")
    public void recover() {
        runtime.recoverBatch(batchSize);
    }
}
