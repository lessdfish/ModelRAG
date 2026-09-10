package com.modelrag.agent.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded recovery scanner; discovery never implies ownership. */
@Component
@Profile("!test")
public class AgentRecoveryWorker {
    private final AgentRuntime runtime;
    private final int batchSize;
    private volatile MeterRegistry metrics;

    public AgentRecoveryWorker(AgentRuntime runtime,
            @org.springframework.beans.factory.annotation.Value("${modelrag.agent.runtime.recovery-batch-size:20}")
            int batchSize) {
        this.runtime = runtime;
        this.batchSize = Math.max(1, Math.min(100, batchSize));
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMetrics(MeterRegistry metrics) { this.metrics = metrics; }

    @Scheduled(fixedDelayString = "${modelrag.agent.runtime.recovery-poll-ms:5000}")
    public void recover() {
        var recovered = runtime.recoverBatch(batchSize);
        if (metrics != null) {
            metrics.counter("modelrag.agent.recovery").increment();
            metrics.counter("modelrag.agent.recovery.scans").increment();
            metrics.counter("modelrag.agent.recovery.completed").increment(recovered == null ? 0 : recovered.size());
        }
    }
}
