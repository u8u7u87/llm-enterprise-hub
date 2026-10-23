package com.enterprise.llm.session;

import com.enterprise.llm.model.ChatMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of SessionStorageProvider.
 */
public class InMemorySessionStorageProvider implements SessionStorageProvider {

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    private String buildKey(String tenantId, String sessionId) {
        return (tenantId != null ? tenantId : "default") + ":" + sessionId;
    }

    @Override
    public void saveSession(ChatSession session) {
        if (session == null || session.sessionId() == null) {
            return;
        }
        String key = buildKey(session.tenantId(), session.sessionId());
        sessions.put(key, session);
    }

    @Override
    public Optional<ChatSession> getSession(String tenantId, String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(buildKey(tenantId, sessionId)));
    }

    @Override
    public List<ChatSession> listSessions(String tenantId, int limit, int offset) {
        String prefix = (tenantId != null ? tenantId : "default") + ":";
        List<ChatSession> matching = sessions.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(ChatSession::updatedAt).reversed())
                .skip(Math.max(0, offset))
                .limit(limit > 0 ? limit : 20)
                .toList();
        return matching;
    }

    @Override
    public void deleteSession(String tenantId, String sessionId) {
        if (sessionId != null) {
            sessions.remove(buildKey(tenantId, sessionId));
        }
    }

    @Override
    public synchronized void appendMessages(String tenantId, String sessionId, List<ChatMessage> messages) {
        if (sessionId == null || messages == null || messages.isEmpty()) {
            return;
        }
        String key = buildKey(tenantId, sessionId);
        ChatSession existing = sessions.get(key);
        if (existing == null) {
            existing = ChatSession.create(sessionId, tenantId, null, "New Conversation");
        }
        List<ChatMessage> combined = new ArrayList<>(existing.messages());
        combined.addAll(messages);
        ChatSession updated = new ChatSession(
                existing.sessionId(),
                existing.tenantId(),
                existing.userId(),
                existing.title(),
                existing.metadata(),
                existing.createdAt(),
                Instant.now(),
                combined
        );
        sessions.put(key, updated);
    }

    public void clear() {
        sessions.clear();
    }
}
