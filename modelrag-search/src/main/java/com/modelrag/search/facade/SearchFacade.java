package com.modelrag.search.facade;
import com.modelrag.search.dto.*; import java.util.List;
public interface SearchFacade { SearchStages inspect(HybridSearchRequest request); default List<ScoredChunk> search(HybridSearchRequest request){return inspect(request).finalResults();} }
