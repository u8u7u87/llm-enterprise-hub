package com.enterprise.llm.router;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.client.StreamCancellationHandle;
import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHedgingRouterTest {

    private MockWebServer primaryServer;
    private MockWebServer fallbackServer;

    private LlmClient primaryClient;
    private LlmClient fallbackClient;
    private RequestHedgingRouter hedgingRouter;

    @BeforeEach
    void setUp() throws IOException {
        primaryServer = new MockWebServer();
        primaryServer.start();

        fallbackServer = new MockWebServer();
        fallbackServer.start();

        primaryClient = new OpenAiCompatibleLlmClient(primaryServer.url("/").toString(), "pk");
        fallbackClient = new OpenAiCompatibleLlmClient(fallbackServer.url("/").toString(), "fk");

        // Set hedging delay to 300ms for stable tests
        hedgingRouter = new RequestHedgingRouter(primaryClient, fallbackClient, "qwen-fast", 300L);
    }

    @AfterEach
    void tearDown() throws IOException {
        primaryServer.shutdown();
        fallbackServer.shutdown();
    }

    @Test
    @DisplayName("Should use primary when primary responds before hedging threshold")
    void shouldReturnPrimaryWhenFast() {
        primaryServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Primary Fast\"}}]}"));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-gpt", List.of(), false, 0.7);
        ChatCompletionResponse response = hedgingRouter.chatCompletionWithHedging(request);

        assertThat(response.choices().get(0).message().content()).isEqualTo("Primary Fast");
        assertThat(primaryServer.getRequestCount()).isEqualTo(1);
        assertThat(fallbackServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should trigger hedging and win when primary TTFT is slow")
    void shouldHedgeAndWinWhenPrimaryIsSlow() {
        // Primary responds with high latency (800ms > 300ms hedging threshold)
        primaryServer.enqueue(new MockResponse()
                .setHeadersDelay(800, TimeUnit.MILLISECONDS)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Primary Slow\"}}]}"));

        // Fallback responds immediately
        fallbackServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"Hedged Fallback Winner\"}}]}"));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-gpt", List.of(), false, 0.7);
        ChatCompletionResponse response = hedgingRouter.chatCompletionWithHedging(request);

        assertThat(response.choices().get(0).message().content()).isEqualTo("Hedged Fallback Winner");
        assertThat(fallbackServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should hedge streaming when primary TTFT exceeds threshold")
    void shouldHedgeStreamingWhenPrimarySlow() throws Exception {
        primaryServer.enqueue(new MockResponse()
                .setHeadersDelay(800, TimeUnit.MILLISECONDS)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Primary Slow Chunk\"}}]}\n\ndata: [DONE]\n\n"));

        fallbackServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hedged Fast Chunk\"}}]}\n\ndata: [DONE]\n\n"));

        ChatCompletionRequest request = new ChatCompletionRequest("cloud-gpt", List.of(), true, 0.7);
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        StreamCancellationHandle handle = hedgingRouter.streamChatCompletionWithHedging(
                request,
                chunks::add,
                latch::countDown,
                err -> latch.countDown()
        );

        boolean done = latch.await(2, TimeUnit.SECONDS);
        assertThat(done).isTrue();
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).choices().get(0).delta().content()).isEqualTo("Hedged Fast Chunk");
    }
}
