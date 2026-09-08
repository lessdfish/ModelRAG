package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.knowledge.model.RetrievalUnit;
import com.modelrag.knowledge.model.RetrievalUnitType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class G4RetrievalProjectionOutboxTest {
    @Test
    void appendingTheSameBuildUnitIsIdempotentAndDoneIsCounted() {
        TestRetrievalProjectionOutbox outbox = new TestRetrievalProjectionOutbox();
        RetrievalUnit unit = unit(501, 101);

        outbox.appendBatch(501, List.of(unit));
        outbox.appendBatch(501, List.of(unit));

        assertEquals(1, outbox.size());
        var claimed = outbox.claimDue(10);
        assertEquals(1, claimed.size());
        assertEquals("UPSERT_RETRIEVAL_UNIT_V2", claimed.get(0).eventType());
        assertTrue(claimed.get(0).payload().contains("\"retrievalUnitId\":101"));
        assertTrue(outbox.claimDue(10).isEmpty(), "an active lease cannot be claimed twice");
        outbox.markDone(claimed.get(0).id());
        assertEquals(1, outbox.countByBuildAndStatus(501, "DONE"));
        assertFalse(outbox.hasTerminalFailure(501));
    }

    @Test
    void failedEventsRetryAndBecomeTerminalAfterTheBoundedRetryBudget() {
        TestRetrievalProjectionOutbox outbox = new TestRetrievalProjectionOutbox();
        outbox.appendBatch(501, List.of(unit(501, 101)));

        for (int attempt = 1; attempt <= 5; attempt++) {
            long eventId = outbox.claimDue(1).get(0).id();
            outbox.markFailed(eventId, "safe projection failure");
            if (attempt < 5) outbox.forceDue(eventId);
        }

        assertEquals(5, outbox.event(1).retryCount());
        assertEquals("FAILED", outbox.event(1).status());
        assertTrue(outbox.event(1).deadLetter());
        assertTrue(outbox.hasTerminalFailure(501));
        assertEquals(0, outbox.countByBuildAndStatus(501, "FAILED"));
    }

    private RetrievalUnit unit(long buildId, long unitId) {
        return new RetrievalUnit(unitId, 7, 9, 4, 201, buildId, RetrievalUnitType.PARAGRAPH, 0,
                "Policy", "body", "hash-" + unitId, 1, Map.of("source", "test"), Instant.now());
    }
}
