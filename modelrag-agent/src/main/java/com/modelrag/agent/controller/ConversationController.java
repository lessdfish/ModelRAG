package com.modelrag.agent.controller;

import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/conversations") public class ConversationController {
    private final ConversationMemory memory; private final AccessControlService access;
    public ConversationController(ConversationMemory memory,AccessControlService access){this.memory=memory;this.access=access;}
    @PostMapping public ApiResponse<ConversationCreatedView> create(@Valid @RequestBody(required=false) ConversationCreateRequest body){var user=access.currentUser();String title=body==null?"新会话":body.safeTitle();Long datasetId=body==null?null:body.datasetId();if(datasetId!=null)access.requireDatasetAccess(datasetId);long id=memory.create(user.id(),datasetId,title);return ApiResponse.success(new ConversationCreatedView(id,title));}
    @GetMapping public ApiResponse<List<ConversationMemory.Conversation>> list(@RequestParam(defaultValue="false") boolean archived){var user=access.currentUser();return ApiResponse.success(memory.conversations(user.id(),archived));}
    @GetMapping("/{id}/messages") public ApiResponse<List<ConversationMemory.Entry>> messages(@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100")int size){var user=access.currentUser();return ApiResponse.success(memory.history(user.id(),id,page,size));}
    @PostMapping("/{id}/archive") public ApiResponse<Void> archive(@PathVariable long id){var user=access.currentUser();memory.archive(user.id(),id);return ApiResponse.success(null);}
}
