package com.modelrag.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.qa.dto.QaRequest;
import com.modelrag.qa.dto.QaResult;
import com.modelrag.qa.orchestrator.QaOrchestrator;
import com.modelrag.toolgateway.execution.InternalToolHandler;
import com.modelrag.toolgateway.execution.ToolInvocation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Explicit compatibility adapter for the two legacy INTERNAL tool names.
 * The Gateway owns dispatch; this Agent-side adapter owns the QA dependency.
 */
@Component
@Profile("!test")
public class LegacyAgentInternalToolHandler implements InternalToolHandler {
    private final QaOrchestrator qa;
    private final ObjectMapper json;

    public LegacyAgentInternalToolHandler(QaOrchestrator qa, ObjectMapper json) {
        this.qa = qa;
        this.json = json;
    }

    @Override
    public boolean supports(String toolName) {
        return "knowledge_lookup".equals(toolName) || "destructive_operation".equals(toolName);
    }

    @Override
    public String invoke(ToolInvocation invocation) {
        if (!supports(invocation.toolName())) {
            throw new IllegalStateException("未注册的兼容 INTERNAL 工具: " + invocation.toolName());
        }
        try {
            JsonNode input = json.readTree(invocation.input());
            String query = input.path("query").asText(invocation.input());
            QaRequest request = new QaRequest(invocation.datasetId(), query, invocation.conversationId(),
                    invocation.userId(), invocation.userRoles()).withoutConversationMessage();
            QaResult result = qa.answer(request);
            return json.writeValueAsString(result);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("兼容 INTERNAL 工具结果无法编码", error);
        }
    }
}
