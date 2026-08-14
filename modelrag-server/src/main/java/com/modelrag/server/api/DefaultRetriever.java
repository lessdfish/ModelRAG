package com.modelrag.server.api;

import com.modelrag.api.Retriever;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.knowledge.model.Chunk;
import com.modelrag.knowledge.model.Document;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.search.dto.HybridSearchRequest;
import com.modelrag.search.facade.SearchFacade;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Authenticated public retrieval facade over the internal hybrid search pipeline. */
@Service
public class DefaultRetriever implements Retriever {
    private final SearchFacade search;
    private final KnowledgeStore knowledge;
    private final AccessControlService access;

    public DefaultRetriever(SearchFacade search, KnowledgeStore knowledge, AccessControlService access) {
        this.search = search;
        this.knowledge = knowledge;
        this.access = access;
    }

    @Override
    public RetrievalResult retrieve(RetrievalRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "检索问题不能为空");
        }
        var current = access.currentUser();
        if (request.userId() != null && !request.userId().isBlank() && !current.id().equals(request.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能代表其他用户检索知识库");
        }
        if (!current.canAccess(request.datasetId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户无权访问知识库: " + request.datasetId());
        }
        int topK = Math.max(1, Math.min(20, request.topK()));
        var stages = search.inspect(new HybridSearchRequest(request.datasetId(), request.question(), topK));
        Map<Long, Chunk> chunks = knowledge.chunks(request.datasetId()).stream()
                .collect(Collectors.toMap(Chunk::id, Function.identity(), (left, right) -> left));
        Map<Long, Document> documents = knowledge.documents(request.datasetId()).stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
        var evidence = stages.finalResults().stream().limit(topK).map(result -> {
            Chunk chunk = chunks.get(result.chunkId());
            Document document = chunk == null ? null : documents.get(chunk.documentId());
            return new Evidence(result.chunkId(), chunk == null ? 0 : chunk.documentId(),
                    document == null ? "" : document.fileName(), location(chunk), result.content(), result.score());
        }).toList();
        return new RetrievalResult(evidence, new LinkedHashSet<>(stages.degradedComponents()));
    }

    private String location(Chunk chunk) {
        if (chunk == null) return "";
        String page = chunk.metadata().get("page");
        if (page != null && !page.isBlank()) return "page:" + page;
        return chunk.metadata().getOrDefault("titlePath", "chunk:" + chunk.index());
    }
}
