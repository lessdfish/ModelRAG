package com.modelrag.agent.controller;

import com.modelrag.agent.intent.*; import com.modelrag.common.dto.ApiResponse; import com.modelrag.common.security.AccessControlService; import java.util.List; import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping({"/api/v1/knowledge-bases/{datasetId}/intents", "/api/v2/datasets/{datasetId}/intents"}) public class IntentController {
    private final IntentTreeService intents; private final AccessControlService access; public IntentController(IntentTreeService intents,AccessControlService access){this.intents=intents;this.access=access;}
    @GetMapping public ApiResponse<List<IntentNode>> list(@PathVariable long datasetId){access.requireDatasetAccess(datasetId);return ApiResponse.success(intents.list(datasetId));}
    @PostMapping public ApiResponse<IntentNode> create(@PathVariable long datasetId,@RequestBody IntentNode node){access.requireRole("ADMIN");return ApiResponse.success(intents.create(new IntentNode(null,datasetId,node.parentId(),node.name(),node.nodeType(),node.targetType(),node.targetId(),node.description(),node.priority(),node.enabled())));}
    @PutMapping("/{intentId}") public ApiResponse<IntentNode> update(@PathVariable long datasetId,@PathVariable long intentId,@RequestBody IntentNode node){access.requireRole("ADMIN");return ApiResponse.success(intents.update(datasetId,intentId,new IntentNode(intentId,datasetId,node.parentId(),node.name(),node.nodeType(),node.targetType(),node.targetId(),node.description(),node.priority(),node.enabled())));}
    @DeleteMapping("/{intentId}") public ApiResponse<Void> delete(@PathVariable long datasetId,@PathVariable long intentId){access.requireRole("ADMIN");intents.delete(datasetId,intentId);return ApiResponse.success(null);}
}
