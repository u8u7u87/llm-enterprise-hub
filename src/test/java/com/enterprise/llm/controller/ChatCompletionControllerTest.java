package com.enterprise.llm.controller;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.service.ChatExecutionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ChatCompletionController.class, ChatExecutionService.class})
class ChatCompletionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatExecutionService chatExecutionService;

    @MockBean
    private com.enterprise.llm.router.FailoverRoutingService failoverRoutingService;

    @MockBean
    private com.enterprise.llm.tenant.TenantService tenantService;

    @MockBean
    private com.enterprise.llm.ratelimit.TenantRateLimiter tenantRateLimiter;

    @Test
    void shouldReturnSyncChatCompletion() throws Exception {
        ChatCompletionResponse dummyResponse = new ChatCompletionResponse(
                "sync-1", "chat.completion", 123456L, "gpt-4o",
                List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", "Sync Hi"), "stop")),
                null
        );
        Mockito.when(tenantService.authenticateApiKey(any()))
                .thenReturn(java.util.Optional.of(new com.enterprise.llm.tenant.TenantEntity("t1", "T", "k", 10, 100, 0, true)));
        Mockito.when(tenantRateLimiter.tryAcquire(any())).thenReturn(true);
        Mockito.when(failoverRoutingService.chatCompletionWithFailover(any())).thenReturn(dummyResponse);

        String jsonRequest = """
                {
                  "model": "gpt-4o",
                  "messages": [{"role": "user", "content": "Hi"}],
                  "stream": false
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "id": "sync-1",
                          "model": "gpt-4o"
                        }
                        """));
    }

    @Test
    void shouldStreamChatCompletionViaSse() throws Exception {
        Mockito.when(tenantService.authenticateApiKey(any()))
                .thenReturn(java.util.Optional.of(new com.enterprise.llm.tenant.TenantEntity("t1", "T", "k", 10, 100, 0, true)));
        Mockito.when(tenantRateLimiter.tryAcquire(any())).thenReturn(true);
        Mockito.when(failoverRoutingService.streamChatCompletionWithFailover(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<ChatCompletionChunk> onChunk = invocation.getArgument(1);
                    Runnable onComplete = invocation.getArgument(2);

                    onChunk.accept(new ChatCompletionChunk("c1", "chat.completion.chunk", 1L, "gpt-4o",
                            List.of(new ChatCompletionChunk.ChoiceChunk(0, new ChatCompletionChunk.Delta("assistant", "Hello"), null)), null));
                    onComplete.run();
                    return new StreamCancellationHandle(() -> {});
                });

        String jsonRequest = """
                {
                  "model": "gpt-4o",
                  "messages": [{"role": "user", "content": "Hi"}],
                  "stream": true
                }
                """;

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(mvcResult.getResponse().getContentType()).contains("text/event-stream");
        String responseContent = mvcResult.getResponse().getContentAsString();
        assertThat(responseContent).contains("data:");
        assertThat(responseContent).contains("Hello");
        assertThat(responseContent).contains("[DONE]");
    }

    @Test
    void shouldTriggerCancellationOnClientAbort() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Mockito.when(failoverRoutingService.streamChatCompletionWithFailover(any(), any(), any(), any()))
                .thenAnswer(invocation -> new StreamCancellationHandle(() -> cancelled.set(true)));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", List.of(), true, 0.7);
        SseEmitter emitter = chatExecutionService.executeStream(request);

        assertThat(cancelled.get()).isFalse();
        emitter.completeWithError(new java.io.IOException("Client disconnected"));
        assertThat(cancelled.get()).isTrue();
    }
}
