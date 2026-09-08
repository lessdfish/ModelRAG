package com.modelrag.knowledge.model;
public record Document(long id, long datasetId, String fileName, String fileType, String hash, String content, String status, String error, int chunkCount,
        String sourceObjectKey, String artifactObjectKey, String contentHash, Long activeVersionId,
        Long activeIndexBuildId) {
    public Document(long id, long datasetId, String fileName, String fileType, String hash, String content,
            String status, String error, int chunkCount, String sourceObjectKey, String artifactObjectKey,
            String contentHash, Long activeVersionId) {
        this(id, datasetId, fileName, fileType, hash, content, status, error, chunkCount,
                sourceObjectKey, artifactObjectKey, contentHash, activeVersionId, null);
    }

    public Document(long id, long datasetId, String fileName, String fileType, String hash, String content, String status,
            String error, int chunkCount, String sourceObjectKey, String artifactObjectKey, String contentHash) {
        this(id, datasetId, fileName, fileType, hash, content, status, error, chunkCount,
                sourceObjectKey, artifactObjectKey, contentHash, null, null);
    }

    public Document(long id, long datasetId, String fileName, String fileType, String hash, String content, String status, String error, int chunkCount) {
        this(id, datasetId, fileName, fileType, hash, content, status, error, chunkCount, null, null, hash, null, null);
    }

    public Document withStatus(String next, String message, int count) {
        return new Document(id, datasetId, fileName, fileType, hash, content, next, message, count,
                sourceObjectKey, artifactObjectKey, contentHash, activeVersionId, activeIndexBuildId);
    }

    public Document withActiveVersionId(Long versionId) {
        return new Document(id, datasetId, fileName, fileType, hash, content, status, error, chunkCount,
                sourceObjectKey, artifactObjectKey, contentHash, versionId, activeIndexBuildId);
    }

    public Document withActiveIndexBuildId(Long buildId) {
        return new Document(id, datasetId, fileName, fileType, hash, content, status, error, chunkCount,
                sourceObjectKey, artifactObjectKey, contentHash, activeVersionId, buildId);
    }
}
