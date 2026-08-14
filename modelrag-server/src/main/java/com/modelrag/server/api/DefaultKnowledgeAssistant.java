package com.modelrag.server.api;

import com.modelrag.api.KnowledgeAssistant;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import org.springframework.stereotype.Service;

/** Production implementation of the framework-light facade exposed for the future SDK/Starter. */
@Service
public class DefaultKnowledgeAssistant implements KnowledgeAssistant {
    private final QaOrchestrator qa;
    private final AccessControlService access;

    public DefaultKnowledgeAssistant(QaOrchestrator qa, AccessControlService access) {
        this.qa = qa;
        this.access = access;
    }

    @Override
    public AnswerResponse ask(AskRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "问题不能为空");
        }
        var current = access.currentUser();
        if (request.userId() != null && !request.userId().isBlank() && !current.id().equals(request.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能代表其他用户发起问答");
        }
        if (!current.canAccess(request.datasetId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户无权访问知识库: " + request.datasetId());
        }
        var answer = qa.answer(new QaRequest(request.datasetId(), request.question(), request.conversationId(),
                current.id(), current.roles()));
        return new AnswerResponse(answer.answer(), answer.citations().stream()
                .map(citation -> new Citation(citation.chunkId(), citation.documentName(), citation.location(),
                        citation.excerpt()))
                .toList(), answer.refused(), answer.traceId());
    }
}
