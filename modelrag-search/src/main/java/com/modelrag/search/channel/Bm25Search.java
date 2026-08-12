package com.modelrag.search.channel;
import com.modelrag.search.dto.HybridSearchRequest; import com.modelrag.search.dto.ScoredChunk; import java.util.List;
public interface Bm25Search { List<ScoredChunk> search(HybridSearchRequest request, int recallSize); }
