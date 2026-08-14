package com.modelrag.server.api;

import com.modelrag.agent.memory.LongTermMemoryService;
import com.modelrag.api.LongTermMemoryStore;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Authenticated memory SPI backed by the production PostgreSQL memory service. */
@Service
@Profile("!test")
public class DefaultLongTermMemoryStore implements LongTermMemoryStore {
    private final LongTermMemoryService memory;
    private final AccessControlService access;

    public DefaultLongTermMemoryStore(LongTermMemoryService memory, AccessControlService access) {
        this.memory = memory;
        this.access = access;
    }

    @Override
    public List<Memory> find(String userId, Long datasetId, String query, int limit) {
        var current = requireSameUser(userId);
        requireDataset(current, datasetId);
        return memory.retrieveRelevant(current.id(), datasetId, query, limit, false).stream().map(this::view).toList();
    }

    @Override
    public Memory save(Memory value) {
        if (value == null) throw new BusinessException(ErrorCode.VALIDATION, "记忆不能为空");
        var current = requireSameUser(value.userId());
        requireDataset(current, value.datasetId());
        var saved = memory.upsert(new LongTermMemoryService.Memory(value.id(), current.id(), value.datasetId(),
                value.scope(), value.type(), value.memoryKey(), value.content(), value.status(), .5, .8,
                value.expiresAt()));
        return view(saved);
    }

    @Override
    public void delete(String userId, String memoryId) {
        memory.delete(requireSameUser(userId).id(), memoryId);
    }

    private com.modelrag.common.security.RequestUser requireSameUser(String userId) {
        var current = access.currentUser();
        if (userId != null && !userId.isBlank() && !current.id().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能访问其他用户的长期记忆");
        }
        return current;
    }

    private void requireDataset(com.modelrag.common.security.RequestUser current, Long datasetId) {
        if (datasetId != null && !current.canAccess(datasetId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户无权访问知识库: " + datasetId);
        }
    }

    private Memory view(LongTermMemoryService.Memory value) {
        return new Memory(value.id(), value.userId(), value.datasetId(), value.scope(), value.type(),
                value.memoryKey(), value.content(), value.status(), value.expiresAt());
    }
}
