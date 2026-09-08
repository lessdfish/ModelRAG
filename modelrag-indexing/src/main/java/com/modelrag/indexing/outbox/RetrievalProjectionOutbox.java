package com.modelrag.indexing.outbox;

import com.modelrag.knowledge.model.RetrievalUnit;
import java.util.List;

/** Durable V2 lexical projection boundary with bounded claiming and retry semantics. */
public interface RetrievalProjectionOutbox {
    void appendBatch(long buildId, List<RetrievalUnit> units);

    List<RetrievalProjectionOutboxEvent> claimDue(int limit);

    void markDone(long id);

    void markFailed(long id, String safeError);

    long countByBuildAndStatus(long buildId, String status);

    boolean hasTerminalFailure(long buildId);
}
