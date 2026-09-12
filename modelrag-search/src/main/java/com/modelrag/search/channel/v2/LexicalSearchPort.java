package com.modelrag.search.channel.v2;

import com.modelrag.search.dto.RetrievalCandidate;
import java.util.List;

/** V2 lexical retrieval port; it is independent from the legacy chunk/BM25 model. */
public interface LexicalSearchPort {
    record ActiveValidatedResult(List<RetrievalCandidate> candidates, boolean truncated) {
        public ActiveValidatedResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    List<RetrievalCandidate> search(LexicalSearchRequest request);

    /**
     * Large active-build-scope path. Implementations must use bounded recall and
     * validate every returned unit against the PostgreSQL active pointers.
     */
    default List<RetrievalCandidate> searchActiveValidated(LexicalSearchRequest request) {
        return search(request);
    }

    default ActiveValidatedResult searchActiveValidatedResult(LexicalSearchRequest request) {
        return new ActiveValidatedResult(searchActiveValidated(request), false);
    }

    /**
     * Performs an indexed, document-scoped lookup over the V2 retrieval-unit
     * projection. Implementations must keep the PostgreSQL active-unit
     * validation used by {@link #search(LexicalSearchRequest)}.
     */
    default List<RetrievalCandidate> findInDocument(DocumentLexicalSearchRequest request) {
        return List.of();
    }
}
