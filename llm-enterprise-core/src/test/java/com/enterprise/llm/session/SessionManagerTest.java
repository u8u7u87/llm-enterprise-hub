package com.enterprise.llm.session;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SessionManagerTest {

    private InMemorySessionStorageProvider storageProvider;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        storageProvider = new InMemorySessionStorageProvider();
        sessionManager = new SessionManager(storageProvider);
    }

    @Test
    @DisplayName("Should create, retrieve, list and delete sessions")
    void testBasicSessionLifecycle() {
        ChatSession session = sessionManager.getOrCreateSession("tenant-1", "sess-100", "user-1", "Test Title");
        assertThat(session).isNotNull();
        assertThat(session.sessionId()).isEqualTo("sess-100");
        assertThat(session.title()).isEqualTo("Test Title");

        Optional<ChatSession> retrieved = sessionManager.getSession("tenant-1", "sess-100");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().sessionId()).isEqualTo("sess-100");

        List<ChatSession> list = sessionManager.listSessions("tenant-1", 10, 0);
        assertThat(list).hasSize(1);

        sessionManager.deleteSession("tenant-1", "sess-100");
        assertThat(sessionManager.getSession("tenant-1", "sess-100")).isEmpty();
    }

    @Test
    @DisplayName("Should merge historical messages when client sends delta prompt")
    void testMergeSessionHistoryDelta() {
        String tenantId = "tenant-1";
        String sessionId = "sess-200";

        // Seed session with turn 1
        sessionManager.getOrCreateSession(tenantId, sessionId, "user-1", "Conversation 1");
        sessionManager.appendMessages(tenantId, sessionId, List.of(
                ChatMessage.system("You are a helpful assistant"),
                ChatMessage.user("Hi, I like apples"),
                ChatMessage.assistant("Hello! Apples are delicious.")
        ));

        // Client sends turn 2 delta (with a system message and user message)
        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(
                        ChatMessage.system("You are a helpful assistant"),
                        ChatMessage.user("What fruit do I like?")
                ),
                false,
                0.7,
                100,
                null,
                null,
                sessionId,
                "user-1"
        );

        ChatCompletionRequest merged = sessionManager.mergeSessionHistory(request, tenantId);

        assertThat(merged.messages()).hasSize(4);
        assertThat(merged.messages().get(0).role()).isEqualTo("system");
        assertThat(merged.messages().get(1).content()).isEqualTo("Hi, I like apples");
        assertThat(merged.messages().get(2).content()).isEqualTo("Hello! Apples are delicious.");
        assertThat(merged.messages().get(3).content()).isEqualTo("What fruit do I like?");
    }

    @Test
    @DisplayName("Should not duplicate if client already sent full conversation history")
    void testMergeSessionHistoryFullEcho() {
        String tenantId = "tenant-1";
        String sessionId = "sess-300";

        sessionManager.getOrCreateSession(tenantId, sessionId, "user-1", "Conversation 2");
        sessionManager.appendMessages(tenantId, sessionId, List.of(
                ChatMessage.user("Hi, I like apples"),
                ChatMessage.assistant("Hello! Apples are delicious.")
        ));

        // Client passes full history + new message
        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(
                        ChatMessage.user("Hi, I like apples"),
                        ChatMessage.assistant("Hello! Apples are delicious."),
                        ChatMessage.user("What fruit do I like?")
                ),
                false,
                0.7,
                100,
                null,
                null,
                sessionId,
                "user-1"
        );

        ChatCompletionRequest merged = sessionManager.mergeSessionHistory(request, tenantId);
        assertThat(merged.messages()).hasSize(3);
        assertThat(merged.messages().get(0).content()).isEqualTo("Hi, I like apples");
        assertThat(merged.messages().get(1).content()).isEqualTo("Hello! Apples are delicious.");
        assertThat(merged.messages().get(2).content()).isEqualTo("What fruit do I like?");
    }
}
