package com.modelrag.knowledge.model;
public record Document(long id, long datasetId, String fileName, String fileType, String hash, String content, String status, String error, int chunkCount) {
    public Document withStatus(String next, String message, int count) { return new Document(id,datasetId,fileName,fileType,hash,content,next,message,count); }
}
