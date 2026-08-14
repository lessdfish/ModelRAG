package com.modelrag.agent.controller;

import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.api.ConversationCreateRequest;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/conversations")
public class ConversationV2Controller {
    private final ConversationMemory memory;
    private final AccessControlService access;

    public ConversationV2Controller(ConversationMemory memory, AccessControlService access) {
        this.memory = memory;
        this.access = access;
    }

    @PostMapping
    public ApiResponse<ConversationMemory.Conversation> create(@Valid @RequestBody ConversationCreateRequest request) {
        var user = access.currentUser();
        if (request.datasetId() != null) access.requireDatasetAccess(request.datasetId());
        long id = memory.create(user.id(), request.datasetId(), request.title() == null ? "新会话" : request.title());
        return ApiResponse.success(memory.conversations(user.id(), true).stream()
                .filter(item -> item.id() == id).findFirst().orElseThrow());
    }

    @GetMapping
    public ApiResponse<List<ConversationMemory.Conversation>> list() {
        var user = access.currentUser();
        return ApiResponse.success(memory.conversations(user.id(), false));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationMemory.Conversation> get(@PathVariable long id) {
        var user = access.currentUser();
        memory.requireOwner(user.id(), id);
        return ApiResponse.success(memory.conversations(user.id(), true).stream()
                .filter(item -> item.id() == id).findFirst().orElseThrow());
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<ConversationMemory.Entry>> messages(@PathVariable long id) {
        var user = access.currentUser();
        memory.requireOwner(user.id(), id);
        return ApiResponse.success(memory.history(user.id(), id, 1, 200));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<Void> archive(@PathVariable long id) {
        var user = access.currentUser();
        memory.archive(user.id(), id);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        var user = access.currentUser();
        memory.delete(user.id(), id);
        return ApiResponse.success(null);
    }

    @GetMapping("/{id}/export")
    public ApiResponse<List<ConversationMemory.Entry>> export(@PathVariable long id) {
        var user = access.currentUser();
        memory.requireOwner(user.id(), id);
        return ApiResponse.success(memory.history(user.id(), id, 1, 10_000));
    }
}
