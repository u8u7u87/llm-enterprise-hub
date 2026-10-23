package com.enterprise.llm.session;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SessionManagementIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private SessionManager sessionManager;

    @MockBean(name = "primaryLlmClient")
    private LlmClient primaryLlmClient;

    private static final String TENANT_ID = "tenant-sess-test";
    private static final String API_KEY = "sk-sess-test-key";

    @BeforeEach
    void setUp() {
        tenantService.registerTenant(new TenantEntity(
                TENANT_ID,
                "Session Test Tenant",
                API_KEY,
                100,
                1000000,
                0,
                true
        ));
        sessionManager.deleteSession(TENANT_ID, "sess-integ-101");
    }

    @Test
    @DisplayName("Verify multi-turn session persistence, automatic history merge, and SessionController REST API")
    void testSessionMultiTurnAndApi() throws Exception {
        String sessionId = "sess-integ-101";

        // Setup mock LLM answers
        ChatCompletionResponse resp1 = new ChatCompletionResponse(
                "resp-1", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Nice to meet you, Alice!"), "stop")),
                new Usage(10, 8, 18)
        );
        ChatCompletionResponse resp2 = new ChatCompletionResponse(
                "resp-2", "chat.completion", System.currentTimeMillis() / 1000, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Your name is Alice."), "stop")),
                new Usage(25, 6, 31)
        );

        Mockito.when(primaryLlmClient.chatCompletion(any())).thenReturn(resp1).thenReturn(resp2);

        // Turn 1: User introduces name
        String turn1Json = """
                {
                  "model": "qwen2.5:0.5b",
                  "session_id": "sess-integ-101",
                  "messages": [
                    {"role": "user", "content": "Hi, my name is Alice"}
                  ]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turn1Json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("Nice to meet you, Alice!"));

        // Turn 2: User asks for name
        String turn2Json = """
                {
                  "model": "qwen2.5:0.5b",
                  "session_id": "sess-integ-101",
                  "messages": [
                    {"role": "user", "content": "What is my name?"}
                  ]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(turn2Json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("Your name is Alice."));

        // Verify primaryLlmClient received history on turn 2
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        Mockito.verify(primaryLlmClient, Mockito.times(2)).chatCompletion(captor.capture());
        ChatCompletionRequest secondCall = captor.getAllValues().get(1);

        // Second call should have merged: [User: Hi, my name is Alice, Assistant: Nice to meet you, Alice!, User: What is my name?]
        assertThat(secondCall.messages()).hasSize(3);
        assertThat(secondCall.messages().get(0).content()).isEqualTo("Hi, my name is Alice");
        assertThat(secondCall.messages().get(1).content()).isEqualTo("Nice to meet you, Alice!");
        assertThat(secondCall.messages().get(2).content()).isEqualTo("What is my name?");

        // Query SessionController GET /v1/sessions/{sessionId}
        mockMvc.perform(get("/v1/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session_id").value(sessionId))
                .andExpect(jsonPath("$.messages.length()").value(4));

        // Query SessionController GET /v1/sessions
        mockMvc.perform(get("/v1/sessions")
                        .header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].session_id").value(sessionId));

        // Delete session
        mockMvc.perform(delete("/v1/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        // Verify deleted
        mockMvc.perform(get("/v1/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isNotFound());
    }
}
