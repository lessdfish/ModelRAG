package com.modelrag.server.api;

import com.modelrag.agent.orchestrator.AgentOrchestrator;
import com.modelrag.agent.orchestrator.AgentResult;
import com.modelrag.api.AssistantAnswerResponse;
import com.modelrag.api.AssistantAskRequest;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/assistant")
public class AssistantV2Controller {
    private final QaOrchestrator qa;
    private final AgentOrchestrator agent;
    private final AccessControlService access;

    public AssistantV2Controller(QaOrchestrator qa, AgentOrchestrator agent, AccessControlService access) {
        this.qa = qa;
        this.agent = agent;
        this.access = access;
    }

    @PostMapping("/ask")
    public ApiResponse<AssistantAnswerResponse> ask(@Valid @RequestBody AssistantAskRequest request) {
        var user = access.currentUser();
        access.requireDatasetAccess(request.datasetId());
        QaRequest internal = new QaRequest(request.datasetId(), request.question(), request.conversationId(),
                user.id(), user.roles());
        if (request.agent()) return ApiResponse.success(agentResponse(agent.execute(internal)));
        return ApiResponse.success(qaResponse(qa.answer(internal)));
    }

    private AssistantAnswerResponse qaResponse(QaResult result) {
        return new AssistantAnswerResponse("DIRECT_RAG", "DONE", result.answer(),
                result.citations().stream().map(c -> new AssistantAnswerResponse.Citation(
                        c.chunkId(), c.documentId(), c.documentName(), c.location(), c.indexVersion(),
                        c.excerpt(), c.score())).toList(), result.confidence(), result.refused(),
                result.traceId(), null, null, java.util.List.of("ANSWER"), result.degradedComponents());
    }

    private AssistantAnswerResponse agentResponse(AgentResult result) {
        return new AssistantAnswerResponse(result.route().name(), result.status(), result.answer(),
                result.citations().stream().map(c -> new AssistantAnswerResponse.Citation(
                        c.chunkId(), c.documentId(), c.documentName(), c.location(), c.indexVersion(),
                        c.excerpt(), c.score())).toList(), result.confidence(), result.refused(),
                result.traceId(), result.executionId(), result.approvalId(), result.steps(), result.degradedComponents());
    }
}
