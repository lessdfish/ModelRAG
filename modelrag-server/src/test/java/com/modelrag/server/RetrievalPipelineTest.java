package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelrag.knowledge.model.Chunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.knowledge.repository.ChunkRepository;
import com.modelrag.knowledge.repository.ChunkWindow;
import com.modelrag.knowledge.repository.jdbc.JdbcChunkRepository;
import com.modelrag.qa.orchestrator.RetrievalPipeline;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.dto.ScoredChunk;
import com.modelrag.search.dto.SearchStages;
import com.modelrag.search.facade.SearchFacade;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RetrievalPipelineTest {
    private static final long DATASET_ID = 7;

    @Test
    void parentExpansionUsesTheSelectedChunkParentGroup() {
        ChunkRepository store = mock(ChunkRepository.class);
        Chunk center = chunk(2, 10, 2, "child", 100L);
        Chunk first = chunk(1, 10, 1, "first", 100L);
        Chunk last = chunk(3, 10, 3, "last", 100L);
        when(store.findActiveByIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of(center));
        when(store.findActiveByParentIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of(center, first, last));

        RetrievalPipeline.RetrievalResult result = pipeline(store, selected(center)).retrieve(DATASET_ID, "query", 1, 0);

        assertEquals("first\nchild\nlast", result.contextChunks().get(0).content());
        verify(store, never()).findActiveByDatasetId(DATASET_ID);
    }

    @Test
    void neighborExpansionUsesOneBoundedBatchOfWindows() {
        ChunkRepository store = mock(ChunkRepository.class);
        Chunk center = chunk(2, 10, 2, "center", null);
        Chunk before = chunk(1, 10, 1, "before", null);
        Chunk after = chunk(3, 10, 3, "after", null);
        when(store.findActiveByIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of(center));
        when(store.findActiveByParentIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of());
        when(store.findActiveNeighbors(eq(DATASET_ID), anyCollection())).thenReturn(List.of(after, center, before));

        RetrievalPipeline.RetrievalResult result = pipeline(store, selected(center)).retrieve(DATASET_ID, "query", 1, 0);

        assertEquals("before\ncenter\nafter", result.contextChunks().get(0).content());
        verify(store).findActiveNeighbors(DATASET_ID, List.of(new ChunkWindow(10, 1, 3)));
        verify(store, never()).findActiveByDatasetId(DATASET_ID);
    }

    @Test
    void duplicateExpandedChunksAreEmittedOnce() {
        ChunkRepository store = mock(ChunkRepository.class);
        Chunk center = chunk(2, 10, 2, "child", 100L);
        Chunk sibling = chunk(3, 10, 3, "sibling", 100L);
        when(store.findActiveByIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of(center));
        when(store.findActiveByParentIds(eq(DATASET_ID), anyCollection()))
                .thenReturn(List.of(center, center, sibling, sibling));

        String content = pipeline(store, selected(center)).retrieve(DATASET_ID, "query", 1, 0)
                .contextChunks().get(0).content();

        assertEquals(1, occurrences(content, "child"));
        assertEquals(1, occurrences(content, "sibling"));
    }

    @Test
    void duplicateParentContextsAcrossSelectedChunksAreEmittedOnce() {
        ChunkRepository store = mock(ChunkRepository.class);
        Chunk first = chunk(2, 10, 2, "first", 100L);
        Chunk second = chunk(3, 10, 3, "second", 100L);
        Chunk sibling = chunk(4, 10, 4, "sibling", 100L);
        when(store.findActiveByIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of(first, second));
        when(store.findActiveByParentIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of(first, second, sibling));

        RetrievalPipeline.RetrievalResult result = pipeline(store, List.of(
                new ScoredChunk(first.id(), first.content(), 1, "vector", 1),
                new ScoredChunk(second.id(), second.content(), .9, "vector", 2)))
                .retrieve(DATASET_ID, "query", 2, 0);

        assertEquals(1, result.contextChunks().size());
        assertEquals("first\nsecond\nsibling", result.contextChunks().get(0).content());
    }

    @Test
    void inactiveVersionChunksAreNotReturnedWhenTheBoundedVisibilityLookupOmitsThem() {
        ChunkRepository store = mock(ChunkRepository.class);
        ScoredChunk inactive = new ScoredChunk(2, "inactive content", 1, "vector", 1);
        when(store.findActiveByIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of());

        RetrievalPipeline.RetrievalResult result = pipeline(store, List.of(inactive))
                .retrieve(DATASET_ID, "query", 1, 0);

        assertTrue(result.contextChunks().isEmpty());
        assertFalse(result.contextChunks().stream().anyMatch(item -> item.content().contains("inactive content")));
    }

    @Test
    void contextExpansionDoesNotDependOnTheFullDatasetChunksApi() {
        ChunkRepository store = mock(ChunkRepository.class);
        Chunk center = chunk(2, 10, 2, "center", null);
        when(store.findActiveByIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of(center));
        when(store.findActiveByParentIds(eq(DATASET_ID), anyCollection())).thenReturn(List.of());
        when(store.findActiveNeighbors(eq(DATASET_ID), anyCollection())).thenReturn(List.of(center));
        when(store.findActiveByDatasetId(anyLong())).thenThrow(new AssertionError("full dataset chunk loading is forbidden"));

        assertFalse(pipeline(store, selected(center)).retrieve(DATASET_ID, "query", 1, 0).contextChunks().isEmpty());
        verify(store, never()).findActiveByDatasetId(anyLong());
    }

    @Test
    void everyBoundedPostgresQueryKeepsOnlyLiveChunksFromTheActiveDocumentVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcChunkRepository store = new JdbcChunkRepository(jdbc, new ObjectMapper());
        store.findActiveByIds(DATASET_ID, List.of(2L));
        store.findActiveByParentIds(DATASET_ID, List.of(100L));
        store.findActiveNeighbors(DATASET_ID, List.of(new ChunkWindow(10, 1, 3)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(3)).query(sql.capture(), any(RowMapper.class), any(Object[].class));

        assertEquals(3, sql.getAllValues().size());
        assertTrue(sql.getAllValues().stream().allMatch(value -> value.contains("c.delete_time IS NULL")
                && value.contains("d.delete_time IS NULL")
                && value.contains("d.active_index_version > 0")
                && value.contains("c.version=d.active_index_version")));
    }

    private RetrievalPipeline pipeline(ChunkRepository store, List<ScoredChunk> selected) {
        SearchFacade search = mock(SearchFacade.class);
        SearchStages stages = new SearchStages("query", List.of("query"), "query",
                List.of(), List.of(), selected, List.of(), false, selected);
        when(search.inspect(any(HybridSearchRequest.class))).thenReturn(stages);
        return new RetrievalPipeline(search, store);
    }

    private List<ScoredChunk> selected(Chunk chunk) {
        return List.of(new ScoredChunk(chunk.id(), chunk.content(), 1, "vector", 1));
    }

    private Chunk chunk(long id, long documentId, int index, String content, Long parentId) {
        return new Chunk(id, documentId, DATASET_ID, index, content, Map.of(), parentId);
    }

    private int occurrences(String value, String target) {
        return value.split(java.util.regex.Pattern.quote(target), -1).length - 1;
    }
}
