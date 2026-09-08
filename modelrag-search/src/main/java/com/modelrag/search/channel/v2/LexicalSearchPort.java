package com.modelrag.search.channel.v2;

import com.modelrag.search.dto.RetrievalCandidate;
import java.util.List;

/** V2 lexical retrieval port; it is independent from the legacy chunk/BM25 model. */
public interface LexicalSearchPort {
    List<RetrievalCandidate> search(LexicalSearchRequest request);
}
