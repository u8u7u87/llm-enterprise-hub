package com.enterprise.llm.router;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FailoverRoutingServiceTest {

    private MockWebServer primaryServer;
    private MockWebServer fallbackServer;

    private LlmClient primaryClient;
    private LlmClient fallbackClient;
    private FailoverRoutingService routingService;

    @BeforeEach
    void setUp() throws IOException {
        primaryServer = new MockWebServer();
        primaryServer.start();

        fallbackServer = new MockWebServer();
        fallbackServer.start();

        primaryClient = new OpenAiCompatibleLlmClient(primaryServer.url("/").toString(), "primary-key");
        fallbackClient = new OpenAiCompatibleLlmClient(fallbackServer.url("/").toString(), "fallback-key");

        routingService = new FailoverRoutingService(primaryClient, fallbackClient, "qwen2.5:0.5b");
    }

    @AfterEach
    void tearDown() throws IOException {
        primaryServer.shutdown();
        fallbackServer.shutdown();
    }

    @Test
    void shouldRouteToPrimaryWhenHealthy() {
        primaryServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"p-1","model":"cloud-model","choices":[{"message":{"content":"From Primary"}}]}
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-model", List.of(), false, 0.7);
        ChatCompletionResponse response = routingService.chatCompletionWithFailover(request);

        assertThat(response.choices().get(0).message().content()).isEqualTo("From Primary");
        assertThat(primaryServer.getRequestCount()).isEqualTo(1);
        assertThat(fallbackServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    void shouldFailoverToLocalOllamaWhenPrimaryFails() {
        // Primary returns 500 error
        primaryServer.enqueue(new MockResponse().setResponseCode(500).setBody("Cloud overloaded"));

        // Fallback local Ollama returns successfully
        fallbackServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"fb-1","model":"qwen2.5:0.5b","choices":[{"message":{"content":"From Local Ollama Fallback"}}]}
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-model", List.of(), false, 0.7);
        ChatCompletionResponse response = routingService.chatCompletionWithFailover(request);

        assertThat(response.choices().get(0).message().content()).isEqualTo("From Local Ollama Fallback");
        assertThat(primaryServer.getRequestCount()).isEqualTo(1);
        assertThat(fallbackServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldFailoverStreamingWhenPrimaryFails() throws Exception {
        primaryServer.enqueue(new MockResponse().setResponseCode(503));

        fallbackServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Fallback stream\"}}]}\n\ndata: [DONE]\n\n"));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-model", List.of(), true, 0.7);
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        routingService.streamChatCompletionWithFailover(
                request,
                chunks::add,
                latch::countDown,
                err -> latch.countDown()
        );

        boolean done = latch.await(5, TimeUnit.SECONDS);
        assertThat(done).isTrue();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).choices().get(0).delta().content()).isEqualTo("Fallback stream");
    }

    @Test
    void shouldSelfHealWhenPrimaryRecoversAfterCooldown() throws Exception {
        com.enterprise.llm.resilience.CircuitBreakerConfig fastConfig = 
                new com.enterprise.llm.resilience.CircuitBreakerConfig(2, 100L, 1, 1);
        com.enterprise.llm.resilience.CircuitBreaker customBreaker = 
                new com.enterprise.llm.resilience.CircuitBreaker("test-primary", fastConfig);
        routingService = new FailoverRoutingService(primaryClient, fallbackClient, "qwen2.5:0.5b", null, customBreaker);

        // Fail 1
        primaryServer.enqueue(new MockResponse().setResponseCode(500));
        fallbackServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Fallback 1\"}}]}"));
        ChatCompletionRequest req = new ChatCompletionRequest("cloud-model", List.of(), false, 0.7);
        routingService.chatCompletionWithFailover(req);

        // Fail 2 -> Trips breaker to OPEN
        primaryServer.enqueue(new MockResponse().setResponseCode(500));
        fallbackServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Fallback 2\"}}]}"));
        routingService.chatCompletionWithFailover(req);

        assertThat(customBreaker.getState()).isEqualTo(com.enterprise.llm.resilience.CircuitBreakerState.OPEN);

        // Immediate next call should bypass primary completely
        fallbackServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Fallback 3 Bypassed\"}}]}"));
        ChatCompletionResponse resp3 = routingService.chatCompletionWithFailover(req);
        assertThat(resp3.choices().get(0).message().content()).isEqualTo("Fallback 3 Bypassed");
        assertThat(primaryServer.getRequestCount()).isEqualTo(2); // No new request to primary

        // Wait for cooldown
        Thread.sleep(120);

        // Primary is now healthy!
        primaryServer.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Primary Healed!\"}}]}"));
        ChatCompletionResponse healResp = routingService.chatCompletionWithFailover(req);

        // Must succeed from Primary and self-heal
        assertThat(healResp.choices().get(0).message().content()).isEqualTo("Primary Healed!");
        assertThat(customBreaker.getState()).isEqualTo(com.enterprise.llm.resilience.CircuitBreakerState.CLOSED);
        assertThat(primaryServer.getRequestCount()).isEqualTo(3);
    }
}

