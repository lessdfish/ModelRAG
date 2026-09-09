package com.modelrag.search.channel.v2;

import com.modelrag.search.dto.RetrievalCandidate;
import java.util.List;

/** V2 lexical retrieval port; it is independent from the legacy chunk/BM25 model. */
public interface LexicalSearchPort {
    List<RetrievalCandidate> search(LexicalSearchRequest request);

    /**
     * Performs an indexed, document-scoped lookup over the V2 retrieval-unit
     * projection. Implementations must keep the PostgreSQL active-unit
     * validation used by {@link #search(LexicalSearchRequest)}.
     */
    default List<RetrievalCandidate> findInDocument(DocumentLexicalSearchRequest request) {
        return List.of();
    }
}
