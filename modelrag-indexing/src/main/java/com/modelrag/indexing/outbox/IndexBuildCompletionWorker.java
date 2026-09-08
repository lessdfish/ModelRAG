package com.modelrag.indexing.outbox;

import com.modelrag.common.exception.SafeErrorSummary;
import com.modelrag.indexing.pipeline.stage.IndexVerificationStage;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import com.modelrag.knowledge.repository.IndexBuildRepository;
import com.modelrag.knowledge.service.IndexBuildLifecycleService;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Completes only bounded, fully delivered V2 builds and delegates cutover to the G3 lifecycle service. */
@Component
@Profile("!test")
public class IndexBuildCompletionWorker {
    private static final int MAX_BUILDS_PER_RUN = 50;
    private final IndexBuildRepository builds;
    private final RetrievalProjectionOutbox outbox;
    private final IndexBuildLifecycleService lifecycle;
    private final IndexVerificationStage verification;

    public IndexBuildCompletionWorker(IndexBuildRepository builds, RetrievalProjectionOutbox outbox,
            IndexBuildLifecycleService lifecycle, IndexVerificationStage verification) {
        this.builds = builds;
        this.outbox = outbox;
        this.lifecycle = lifecycle;
        this.verification = verification;
    }

    @Scheduled(fixedDelayString = "${modelrag.index.v2.completion-poll-ms:1000}")
    public void completeDueBuilds() {
        for (IndexBuild build : builds.findByState(IndexBuildState.LEXICAL_SYNCING, MAX_BUILDS_PER_RUN)) {
            complete(build);
        }
    }

    private void complete(IndexBuild build) {
        try {
            if (outbox.hasTerminalFailure(build.id())) {
                fail(build.id(), new IllegalStateException("V2 词法投影存在不可重试事件"));
                return;
            }
            long lexicalCount = outbox.countByBuildAndStatus(build.id(), "DONE");
            if (lexicalCount != build.unitCount()) return;

            lifecycle.transition(build.id(), IndexBuildState.LEXICAL_SYNCING, IndexBuildState.VERIFYING);
            IndexBuild checking = builds.findById(build.id()).orElseThrow(
                    () -> new IllegalStateException("V2 构建在校验前不可见"));
            IndexVerificationStage.VerificationResult result = verification.verify(checking, lexicalCount);
            lifecycle.updateCounts(build.id(), result.nodeCount(), result.unitCount(), result.vectorCount(),
                    result.lexicalCount());
            lifecycle.markReady(build.id());
            lifecycle.activateBuild(build.id());
        } catch (Exception error) {
            fail(build.id(), error);
        }
    }

    private void fail(long buildId, Exception error) {
        try {
            builds.findById(buildId).ifPresent(current -> {
                if (current.state() != IndexBuildState.ACTIVE
                        && current.state() != IndexBuildState.SUPERSEDED
                        && current.state() != IndexBuildState.FAILED) {
                    lifecycle.fail(buildId, SafeErrorSummary.of(error));
                }
            });
        } catch (RuntimeException ignored) {
            // A competing worker may have completed or failed the build; its state is authoritative.
        }
    }
}
