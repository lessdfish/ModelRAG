package com.modelrag.knowledge.model;
public record Document(long id, long datasetId, String fileName, String fileType, String hash, String content, String status, String error, int chunkCount,
        String sourceObjectKey, String artifactObjectKey, String contentHash) {
    public Document(long id, long datasetId, String fileName, String fileType, String hash, String content, String status, String error, int chunkCount) {
        this(id, datasetId, fileName, fileType, hash, content, status, error, chunkCount, null, null, hash);
    }
    public Document withStatus(String next, String message, int count) { return new Document(id,datasetId,fileName,fileType,hash,content,next,message,count,sourceObjectKey,artifactObjectKey,contentHash); }
}
