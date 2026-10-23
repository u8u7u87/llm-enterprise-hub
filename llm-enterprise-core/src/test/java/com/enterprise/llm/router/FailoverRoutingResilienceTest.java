package com.enterprise.llm.router;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.context.ContextBudgetProperties;
import com.enterprise.llm.context.ContextManager;
import com.enterprise.llm.context.HeuristicContextDistiller;
import com.enterprise.llm.exception.LlmAuthenticationException;
import com.enterprise.llm.exception.LlmContentFilterException;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.token.TokenEstimator;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverRoutingResilienceTest {

    private MockWebServer primaryServer;
    private MockWebServer fallbackServer;

    private LlmClient primaryClient;
    private LlmClient fallbackClient;
    private ContextManager contextManager;
    private FailoverRoutingService routingService;

    @BeforeEach
    void setUp() throws IOException {
        primaryServer = new MockWebServer();
        primaryServer.start();

        fallbackServer = new MockWebServer();
        fallbackServer.start();

        primaryClient = new OpenAiCompatibleLlmClient(primaryServer.url("/").toString(), "primary-key");
        fallbackClient = new OpenAiCompatibleLlmClient(fallbackServer.url("/").toString(), "fallback-key");

        ContextBudgetProperties budget = new ContextBudgetProperties();
        budget.setMaxContextTokens(100);
        budget.setKeepLastMessages(2);
        contextManager = new ContextManager(budget, new TokenEstimator(), new HeuristicContextDistiller());

        routingService = new FailoverRoutingService(primaryClient, fallbackClient, "qwen2.5:0.5b", contextManager);
        routingService.setBackoffBaseMs(10L); // fast backoff for tests
    }

    @AfterEach
    void tearDown() throws IOException {
        primaryServer.shutdown();
        fallbackServer.shutdown();
    }

    @Test
    void shouldAutoCompactAndSelfHealWhenContextLengthExceeded() {
        // First attempt returns 400 context_length_exceeded
        primaryServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "error": {
                            "message": "Maximum context length exceeded: 4096 tokens max",
                            "type": "invalid_request_error",
                            "code": "context_length_exceeded"
                          }
                        }
                        """));

        // Second attempt (after auto emergency compaction) succeeds!
        primaryServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "self-healed-1",
                          "model": "cloud-primary",
                          "choices": [{"message": {"role": "assistant", "content": "Self-healed answer"}}]
                        }
                        """));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("System prompt"));
        for (int i = 0; i < 10; i++) {
            messages.add(ChatMessage.user("User question " + i));
            messages.add(ChatMessage.assistant("Assistant answer " + i));
        }

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-primary", messages, false, 0.7);
        ChatCompletionResponse response = routingService.chatCompletionWithFailover(request);

        assertThat(response).isNotNull();
        assertThat(response.choices().get(0).message().content()).isEqualTo("Self-healed answer");
        assertThat(primaryServer.getRequestCount()).isEqualTo(2);
        assertThat(fallbackServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    void shouldRetryWithBackoffOn429RateLimit() {
        // First attempt returns 429 with retry-after header
        primaryServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "error": {
                            "message": "Rate limit reached. Please wait.",
                            "type": "requests",
                            "code": "rate_limit_exceeded"
                          }
                        }
                        """));

        // Second attempt succeeds
        primaryServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "rate-retry-1",
                          "model": "cloud-primary",
                          "choices": [{"message": {"role": "assistant", "content": "Recovered from 429"}}]
                        }
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-primary", List.of(ChatMessage.user("Hello")), false, 0.7);
        ChatCompletionResponse response = routingService.chatCompletionWithFailover(request);

        assertThat(response.choices().get(0).message().content()).isEqualTo("Recovered from 429");
        assertThat(primaryServer.getRequestCount()).isEqualTo(2);
        assertThat(fallbackServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    void shouldFastFailOnContentFilterWithoutFallback() {
        primaryServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "error": {
                            "message": "The response was filtered due to the prompt triggering Azure OpenAI's content management policy.",
                            "type": "invalid_request_error",
                            "code": "content_filter"
                          }
                        }
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-primary", List.of(ChatMessage.user("Unsafe query")), false, 0.7);

        assertThatThrownBy(() -> routingService.chatCompletionWithFailover(request))
                .isInstanceOf(LlmContentFilterException.class)
                .hasMessageContaining("content management policy");

        // Primary was called once, and fallback was NEVER called
        assertThat(primaryServer.getRequestCount()).isEqualTo(1);
        assertThat(fallbackServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    void shouldFastFailOnAuthenticationExceptionWithoutFallback() {
        primaryServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "error": {
                            "message": "Incorrect API key provided.",
                            "type": "invalid_request_error",
                            "code": "invalid_api_key"
                          }
                        }
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-primary", List.of(ChatMessage.user("Hi")), false, 0.7);

        assertThatThrownBy(() -> routingService.chatCompletionWithFailover(request))
                .isInstanceOf(LlmAuthenticationException.class)
                .hasMessageContaining("Incorrect API key");

        assertThat(primaryServer.getRequestCount()).isEqualTo(1);
        assertThat(fallbackServer.getRequestCount()).isEqualTo(0);
    }
}
