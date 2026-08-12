package com.modelrag.common.vector;
import java.util.List;
public interface VectorStore { void upsert(List<VectorDocument> documents); List<SearchResult> search(SearchRequest request); void deleteDocument(long documentId); }
