package com.modelrag.search.reranker;

import com.modelrag.api.Retriever;
import com.modelrag.search.dto.ScoredChunk;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Public reranker SPI adapted to the optional bounded internal reranker. */
@Service
public class DefaultApiReranker implements com.modelrag.api.Reranker {
    private final Reranker reranker;

    public DefaultApiReranker(Reranker reranker) {
        this.reranker = reranker;
    }

    @Override
    public List<Retriever.Evidence> rerank(String question, List<Retriever.Evidence> candidates, Duration timeout) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Map<Long, Retriever.Evidence> source = candidates.stream()
                .collect(Collectors.toMap(Retriever.Evidence::chunkId, Function.identity(), (left, right) -> left));
        List<ScoredChunk> values = candidates.stream()
                .map(value -> new ScoredChunk(value.chunkId(), value.content(), value.score(), "candidate", 0))
                .toList();
        return reranker.rerank(0, question, values, timeout).stream().map(value -> {
            Retriever.Evidence original = source.get(value.chunkId());
            return new Retriever.Evidence(value.chunkId(), original.documentId(), original.documentName(),
                    original.location(), value.content(), value.score());
        }).toList();
    }
}
