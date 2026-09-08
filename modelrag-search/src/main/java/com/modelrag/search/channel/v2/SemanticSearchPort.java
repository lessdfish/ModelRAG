package com.modelrag.search.channel.v2;

import com.modelrag.search.dto.RetrievalCandidate;
import java.util.List;

/** V2 semantic retrieval port. Query embedding is supplied by orchestration. */
public interface SemanticSearchPort {
    List<RetrievalCandidate> search(SemanticSearchRequest request);
}
