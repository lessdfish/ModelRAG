package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.indexing.pipeline.IndexBuildCoordinator;
import com.modelrag.indexing.pipeline.IndexingPipeline;
import com.modelrag.indexing.pipeline.LegacyChunkIndexingPipeline;
import com.modelrag.indexing.pipeline.LegacyIndexResult;
import com.modelrag.knowledge.model.IndexBuild;
import com.modelrag.knowledge.model.IndexBuildState;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class G4DualWriteIntegrationTest {
    @Test
    void v1RemainsTheAuthoritativePathWhenShadowBuildIsDisabled() {
        LegacyChunkIndexingPipeline legacy = mock(LegacyChunkIndexingPipeline.class);
        IndexBuildCoordinator v2 = mock(IndexBuildCoordinator.class);
        when(legacy.index(9)).thenReturn(new LegacyIndexResult(true, 4, 2, null));

        new IndexingPipeline(legacy, v2, false).index(9);

        verify(legacy).index(9);
        verify(v2, never()).start(9);
    }

    @Test
    void successfulV1IndexIsFollowedByAnIndependentV2ShadowStart() {
        LegacyChunkIndexingPipeline legacy = mock(LegacyChunkIndexingPipeline.class);
        IndexBuildCoordinator v2 = mock(IndexBuildCoordinator.class);
        when(legacy.index(9)).thenReturn(new LegacyIndexResult(true, 4, 2, null));
        when(v2.start(9)).thenReturn(build(IndexBuildState.LEXICAL_SYNCING));

        new IndexingPipeline(legacy, v2, true).index(9);

        verify(legacy).index(9);
        verify(v2).start(9);
    }

    @Test
    void v2FailureCannotTurnASuccessfulV1IndexIntoAFailure() {
        LegacyChunkIndexingPipeline legacy = mock(LegacyChunkIndexingPipeline.class);
        IndexBuildCoordinator v2 = mock(IndexBuildCoordinator.class);
        when(legacy.index(9)).thenReturn(new LegacyIndexResult(true, 4, 2, null));
        when(v2.start(9)).thenThrow(new IllegalStateException("shadow build failed"));

        assertDoesNotThrow(() -> new IndexingPipeline(legacy, v2, true).index(9));
        verify(legacy).index(9);
        verify(v2).start(9);
    }

    private IndexBuild build(IndexBuildState state) {
        return new IndexBuild(17, 7, 9, 4, 1, state, "qwen3-v1", "default", 1, 1, 1, 0, null,
                Map.of(), Instant.now(), Instant.now(), null, null, null);
    }
}
