package com.enterprise.llm.controller;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.exception.LlmAuthenticationException;
import com.enterprise.llm.exception.LlmContentFilterException;
import com.enterprise.llm.guard.OutputGuardrailService;
import com.enterprise.llm.guard.PromptGuardrailService;
import com.enterprise.llm.metrics.MetricsRecordService;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.quota.TenantQuotaService;
import com.enterprise.llm.ratelimit.RateLimitResult;
import com.enterprise.llm.ratelimit.TenantRateLimiter;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.service.ChatExecutionService;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
import com.enterprise.llm.token.TokenEstimator;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private FailoverRoutingService failoverRoutingService;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private TenantRateLimiter tenantRateLimiter;

    @MockBean
    private PromptGuardrailService promptGuardrailService;

    @MockBean
    private TenantQuotaService tenantQuotaService;

    @MockBean
    private OutputGuardrailService outputGuardrailService;

    @MockBean
    private TokenEstimator tokenEstimator;

    @MockBean
    private CacheEngine cacheEngine;

    @MockBean
    private MetricsRecordService metricsRecordService;

    @Test
    void shouldReturnSyncChatCompletion() throws Exception {
        ChatCompletionResponse dummyResponse = new ChatCompletionResponse(
                "sync-1", "chat.completion", 123456L, "gpt-4o",
                List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", "Sync Hi"), "stop")),
                null
        );
        Mockito.when(tenantService.authenticateApiKey(any()))
                .thenReturn(Optional.of(new TenantEntity("t1", "T", "k", 10, 100, 0, true)));
        Mockito.when(tenantRateLimiter.tryAcquire(any())).thenReturn(true);
        Mockito.when(promptGuardrailService.isSafe(any())).thenReturn(true);
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
                .thenReturn(Optional.of(new TenantEntity("t1", "T", "k", 10, 100, 0, true)));
        Mockito.when(tenantRateLimiter.tryAcquire(any())).thenReturn(true);
        Mockito.when(promptGuardrailService.isSafe(any())).thenReturn(true);
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
        emitter.completeWithError(new IOException("Client disconnected"));
        assertThat(cancelled.get()).isTrue();
    }

    @Test
    void shouldRejectEmptyMessages() throws Exception {
        Mockito.when(tenantService.authenticateApiKey(any()))
                .thenReturn(Optional.of(new TenantEntity("t1", "T", "k", 10, 100, 0, true)));
        Mockito.when(tenantRateLimiter.tryAcquire(any())).thenReturn(true);

        String json = """
                {"model": "qwen2.5:0.5b", "messages": []}
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(Matchers.containsString("messages must not be empty")));
    }

    @Test
    void shouldFormatLlmAuthenticationExceptionAsOpenAiJson() throws Exception {
        Mockito.when(tenantService.authenticateApiKey(any()))
                .thenReturn(Optional.of(new TenantEntity("t1", "T", "k", 10, 100, 0, true)));
        Mockito.when(tenantRateLimiter.tryAcquire(any())).thenReturn(true);
        Mockito.when(promptGuardrailService.isSafe(any())).thenReturn(true);
        Mockito.when(failoverRoutingService.chatCompletionWithFailover(any()))
                .thenThrow(new LlmAuthenticationException("Invalid API key provided", "invalid_api_key"));

        String jsonRequest = """
                {
                  "model": "gpt-4o",
                  "messages": [{"role": "user", "content": "Hi"}]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {
                          "error": {
                            "message": "Invalid API key provided",
                            "type": "authentication_error",
                            "code": "invalid_api_key"
                          }
                        }
                        """));
    }

    @Test
    void shouldFormatLlmContentFilterExceptionAsOpenAiJson() throws Exception {
        Mockito.when(tenantService.authenticateApiKey(any()))
                .thenReturn(Optional.of(new TenantEntity("t1", "T", "k", 10, 100, 0, true)));
        Mockito.when(tenantRateLimiter.tryAcquire(any())).thenReturn(true);
        Mockito.when(promptGuardrailService.isSafe(any())).thenReturn(true);
        Mockito.when(failoverRoutingService.chatCompletionWithFailover(any()))
                .thenThrow(new LlmContentFilterException("Prompt triggered content filter", "content_filter"));

        String jsonRequest = """
                {
                  "model": "gpt-4o",
                  "messages": [{"role": "user", "content": "Hi"}]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {
                          "error": {
                            "message": "Prompt triggered content filter",
                            "type": "content_filter_error",
                            "code": "content_filter"
                          }
                        }
                        """));
    }

    @Test
    void shouldRejectWhenTpmExceeded() throws Exception {
        Mockito.when(tenantService.authenticateApiKey(any()))
                .thenReturn(Optional.of(new TenantEntity("t1", "T", "k", 10, 100, 500, 100000, 0, true)));
        Mockito.when(tokenEstimator.estimateTotalReservationTokens(any(), any()))
                .thenReturn(600);
        Mockito.when(tenantRateLimiter.tryAcquireDetailed(any(), Mockito.eq(0)))
                .thenReturn(RateLimitResult.granted());
        Mockito.when(tenantRateLimiter.tryAcquireTpm(any(), Mockito.eq(600)))
                .thenReturn(RateLimitResult.rejected("TPM_EXCEEDED: 1200/500", 2000L));
        Mockito.when(promptGuardrailService.isSafe(any())).thenReturn(true);

        String jsonRequest = """
                {
                  "model": "gpt-4o",
                  "messages": [{"role": "user", "content": "A very large prompt exceeding token limit"}]
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-API-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(Matchers.containsString("TPM_EXCEEDED")));
    }
}
