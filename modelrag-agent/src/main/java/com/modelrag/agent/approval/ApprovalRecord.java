package com.modelrag.agent.approval;
import java.time.Instant;
public record ApprovalRecord(String id, String executionId, String toolName, String params, String status, String approvedBy,
        Instant expiresAt, String requesterUserId, Long datasetId, Long conversationId) {
    public ApprovalRecord(String id, String executionId, String toolName, String params, String status, Instant expiresAt){
        this(id,executionId,toolName,params,status,null,expiresAt,null,null,null);
    }
    public ApprovalRecord(String id, String executionId, String toolName, String params, String status, String approvedBy, Instant expiresAt){
        this(id,executionId,toolName,params,status,approvedBy,expiresAt,null,null,null);
    }
    public ApprovalRecord status(String next){return new ApprovalRecord(id,executionId,toolName,params,next,approvedBy,expiresAt,requesterUserId,datasetId,conversationId);}
    public ApprovalRecord decided(String next,String userId){return new ApprovalRecord(id,executionId,toolName,params,next,userId,expiresAt,requesterUserId,datasetId,conversationId);}
}
