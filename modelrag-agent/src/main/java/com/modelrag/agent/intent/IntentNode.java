package com.modelrag.agent.intent;

public record IntentNode(Long id,long datasetId,Long parentId,String name,String nodeType,String targetType,String targetId,String description,int priority,boolean enabled) {}
