package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.indexing.outbox.IndexBuildCompletionWorker;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitType;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.knowledge.service.IndexBuildLifecycleService;
import com.modelrag.indexing.pipeline.stage.IndexVerificationStage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class G4IndexBuildCompletionWorkerTest {
    @Test
    void competingWorkersTreatLostCompletionCasAsAHealthyNoOp() throws Exception {
        IndexBuild build = new IndexBuild(17, 7, 9, 4, 1, IndexBuildState.LEXICAL_SYNCING,
                "qwen3-v1", "default", 1, 1, 1, 0, null, Map.of(), Instant.now(), Instant.now(), null, null,
                null);
        IndexBuildRepository builds = mock(IndexBuildRepository.class);
        when(builds.findByState(IndexBuildState.LEXICAL_SYNCING, 50)).thenReturn(List.of(build));
        when(builds.findById(build.id())).thenReturn(Optional.of(build));

        TestRetrievalProjectionOutbox outbox = new TestRetrievalProjectionOutbox();
        outbox.appendBatch(build.id(), List.of(new RetrievalUnit(31, 7, 9, 4, 101, build.id(),
                RetrievalUnitType.PARAGRAPH, 0, "Policy", "body", "hash", 1, Map.of(), Instant.now())));
        outbox.markDone(outbox.claimDue(1).get(0).id());

        IndexBuildLifecycleService lifecycle = mock(IndexBuildLifecycleService.class);
        AtomicBoolean winner = new AtomicBoolean();
        when(lifecycle.tryTransition(build.id(), IndexBuildState.LEXICAL_SYNCING, IndexBuildState.VERIFYING))
                .thenAnswer(ignored -> winner.compareAndSet(false, true));
        IndexVerificationStage verification = mock(IndexVerificationStage.class);
        when(verification.verify(any(), eq(1L)))
                .thenReturn(new IndexVerificationStage.VerificationResult(1, 1, 1, 1));
        IndexBuildCompletionWorker first = new IndexBuildCompletionWorker(builds, outbox, lifecycle, verification);
        IndexBuildCompletionWorker second = new IndexBuildCompletionWorker(builds, outbox, lifecycle, verification);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstRun = executor.submit(() -> run(first, ready, start));
            var secondRun = executor.submit(() -> run(second, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            firstRun.get(5, TimeUnit.SECONDS);
            secondRun.get(5, TimeUnit.SECONDS);
        }

        verify(lifecycle, times(2)).tryTransition(build.id(), IndexBuildState.LEXICAL_SYNCING,
                IndexBuildState.VERIFYING);
        verify(lifecycle, times(1)).updateCounts(17, 1, 1, 1, 1);
        verify(lifecycle, times(1)).markReady(build.id());
        verify(lifecycle, times(1)).activateBuild(build.id());
        verify(lifecycle, never()).fail(anyLong(), any(String.class));
        verify(verification, times(1)).verify(any(), eq(1L));
    }

    private void run(IndexBuildCompletionWorker worker, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            worker.completeDueBuilds();
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
