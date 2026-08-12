package com.modelrag.agent.orchestrator;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.RequestUser;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionRegistry {
    public record Scope(String requesterUserId, long datasetId, Long conversationId) {}

    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();

    public void register(String executionId, String requesterUserId, long datasetId, Long conversationId) {
        scopes.put(executionId, new Scope(owner(requesterUserId), datasetId, conversationId));
    }

    public void requireSubscribe(RequestUser user, String executionId) {
        Scope scope = scopes.get(executionId);
        if (scope == null) {
            if (user.hasRole("ADMIN")) return;
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权订阅未知 Agent 执行流: " + executionId);
        }
        if (user.hasRole("ADMIN") || owner(user.id()).equals(scope.requesterUserId())) return;
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权订阅该 Agent 执行流: " + executionId);
    }

    private String owner(String userId) {
        return userId == null || userId.isBlank() ? "global" : userId;
    }
}
