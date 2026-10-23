package com.enterprise.llm.session;

import com.enterprise.llm.model.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * Storage SPI for managing multi-turn chat sessions across tenants.
 */
public interface SessionStorageProvider {

    /**
     * Persist or update session metadata.
     */
    void saveSession(ChatSession session);

    /**
     * Retrieve a session by tenant and sessionId including its messages.
     */
    Optional<ChatSession> getSession(String tenantId, String sessionId);

    /**
     * List sessions belonging to a tenant ordered by latest update time.
     */
    List<ChatSession> listSessions(String tenantId, int limit, int offset);

    /**
     * Delete a session and all its messages.
     */
    void deleteSession(String tenantId, String sessionId);

    /**
     * Append messages to an existing session.
     */
    void appendMessages(String tenantId, String sessionId, List<ChatMessage> messages);
}
