package com.enterprise.llm.session;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Service to manage sessions and transparently bind conversation history.
 */
public class SessionManager {

    private final SessionStorageProvider storageProvider;

    public SessionManager(SessionStorageProvider storageProvider) {
        this.storageProvider = storageProvider != null ? storageProvider : new InMemorySessionStorageProvider();
    }

    public SessionStorageProvider getStorageProvider() {
        return storageProvider;
    }

    public ChatSession getOrCreateSession(String tenantId, String sessionId, String userId, String title) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        return storageProvider.getSession(tenantId, sessionId)
                .orElseGet(() -> {
                    String actualTitle = (title != null && !title.isBlank()) ? title : "Session " + sessionId;
                    ChatSession newSession = ChatSession.create(sessionId, tenantId, userId, actualTitle);
                    storageProvider.saveSession(newSession);
                    return newSession;
                });
    }

    public Optional<ChatSession> getSession(String tenantId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return storageProvider.getSession(tenantId, sessionId);
    }

    public List<ChatSession> listSessions(String tenantId, int limit, int offset) {
        return storageProvider.listSessions(tenantId, limit, offset);
    }

    public void deleteSession(String tenantId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            storageProvider.deleteSession(tenantId, sessionId);
        }
    }

    public void saveSession(ChatSession session) {
        if (session != null) {
            storageProvider.saveSession(session);
        }
    }

    public void appendMessages(String tenantId, String sessionId, List<ChatMessage> messages) {
        if (sessionId != null && !sessionId.isBlank() && messages != null && !messages.isEmpty()) {
            storageProvider.appendMessages(tenantId, sessionId, messages);
        }
    }

    /**
     * If the request contains a sessionId, load the existing session history and combine with request messages.
     * System prompt is retained at index 0 if present.
     */
    public ChatCompletionRequest mergeSessionHistory(ChatCompletionRequest request, String tenantId) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return request;
        }

        String sid = request.sessionId();
        Optional<ChatSession> sessionOpt = storageProvider.getSession(tenantId, sid);
        if (sessionOpt.isEmpty()) {
            // First time this session is seen: create session metadata
            String title = "Session " + sid;
            if (request.messages() != null) {
                for (ChatMessage m : request.messages()) {
                    if ("user".equalsIgnoreCase(m.role()) && m.content() != null && !m.content().isBlank()) {
                        title = m.content().trim();
                        if (title.length() > 30) {
                            title = title.substring(0, 30) + "...";
                        }
                        break;
                    }
                }
            }
            getOrCreateSession(tenantId, sid, request.user(), title);
            return request;
        }

        List<ChatMessage> existingHistory = sessionOpt.get().messages();
        if (existingHistory == null || existingHistory.isEmpty()) {
            return request;
        }

        List<ChatMessage> reqMessages = request.messages() != null ? request.messages() : Collections.emptyList();

        // Check if reqMessages already includes all of existingHistory (client echoed entire conversation)
        if (reqMessages.size() >= existingHistory.size()) {
            boolean allMatch = true;
            for (int i = 0; i < existingHistory.size(); i++) {
                ChatMessage hist = existingHistory.get(i);
                ChatMessage req = reqMessages.get(i);
                if (!hist.role().equalsIgnoreCase(req.role()) || !hist.content().equals(req.content())) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                // Client already provided full history
                return request;
            }
        }

        // Otherwise, client only passed the delta (e.g. system message + latest user message, or just latest user message)
        ChatMessage systemMsg = null;
        List<ChatMessage> delta = new ArrayList<>();
        for (ChatMessage m : reqMessages) {
            if ("system".equalsIgnoreCase(m.role()) && systemMsg == null) {
                systemMsg = m;
            } else {
                delta.add(m);
            }
        }

        List<ChatMessage> merged = new ArrayList<>();
        // 1. System prompt
        if (systemMsg != null) {
            merged.add(systemMsg);
        } else if (!existingHistory.isEmpty() && "system".equalsIgnoreCase(existingHistory.get(0).role())) {
            merged.add(existingHistory.get(0));
        }

        // 2. History (skip historical system message if already added)
        for (ChatMessage h : existingHistory) {
            if ("system".equalsIgnoreCase(h.role())) {
                continue;
            }
            merged.add(h);
        }

        // 3. New Delta messages
        merged.addAll(delta);

        return new ChatCompletionRequest(
                request.model(),
                merged,
                request.stream(),
                request.temperature(),
                request.maxTokens(),
                request.tools(),
                request.toolChoice(),
                request.sessionId(),
                request.user()
        );
    }
}
