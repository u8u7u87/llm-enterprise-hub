package com.enterprise.llm.session;

import com.enterprise.llm.model.ChatMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Domain model representing a multi-turn chat session.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatSession(
        String sessionId,
        String tenantId,
        String userId,
        String title,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt,
        List<ChatMessage> messages
) {
    public ChatSession {
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }
        if (messages == null) {
            messages = Collections.emptyList();
        } else {
            messages = Collections.unmodifiableList(new ArrayList<>(messages));
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    public static ChatSession create(String sessionId, String tenantId, String userId, String title) {
        return new ChatSession(sessionId, tenantId, userId, title, Collections.emptyMap(), Instant.now(), Instant.now(), Collections.emptyList());
    }

    public ChatSession withMessages(List<ChatMessage> newMessages) {
        return new ChatSession(sessionId, tenantId, userId, title, metadata, createdAt, Instant.now(), newMessages);
    }
}
