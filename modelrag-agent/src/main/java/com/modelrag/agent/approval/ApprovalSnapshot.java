package com.modelrag.agent.approval;

/** Immutable public read model for an approval record. */
public record ApprovalSnapshot(
        String id,
        String executionId,
        String toolName,
        String params,
        String status,
        String approvedBy,
        String requesterUserId,
        Long datasetId,
        Long conversationId,
        String createdAt,
        String expiresAt) {
}
