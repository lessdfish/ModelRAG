package com.modelrag.common.security;

public interface ConversationAccess {
    void requireOwner(String userId, Long conversationId);
}
