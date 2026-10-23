package com.enterprise.llm.client;

import com.enterprise.llm.model.ChatCompletionChunk;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleLlmClientTest {

    private MockWebServer server;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        client = new OpenAiCompatibleLlmClient(baseUrl, "test-api-key");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldStreamSseChunksAndComplete() throws Exception {
        String sseBody = """
                data: {"id":"1","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"}}]}
                
                data: {"id":"2","model":"gpt-4o","choices":[{"index":0,"delta":{"content":" world!"}}]}
                
                data: [DONE]
                
                """;

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", List.of(), true, 0.7);
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        CountDownLatch completeLatch = new CountDownLatch(1);

        StreamCancellationHandle handle = client.streamChatCompletion(
                request,
                chunks::add,
                completeLatch::countDown,
                error -> completeLatch.countDown()
        );

        assertThat(handle).isNotNull();
        boolean completed = completeLatch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).choices().get(0).delta().content()).isEqualTo("Hello");
        assertThat(chunks.get(1).choices().get(0).delta().content()).isEqualTo(" world!");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-api-key");
    }

    @Test
    void shouldSupportCancellationDuringStreaming() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody("data: {\"choices\":[{\"delta\":{\"content\":\"Chunk1\"}}]}\n\n", 1024));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", List.of(), true, 0.7);
        CountDownLatch firstChunkLatch = new CountDownLatch(1);

        StreamCancellationHandle handle = client.streamChatCompletion(
                request,
                chunk -> firstChunkLatch.countDown(),
                () -> {},
                error -> {}
        );

        firstChunkLatch.await(2, TimeUnit.SECONDS);
        assertThat(handle.isCancelled()).isFalse();

        handle.cancel();
        assertThat(handle.isCancelled()).isTrue();
    }

    @Test
    void shouldExecuteNonStreamingChatCompletion() throws Exception {
        String responseBody = """
                {
                  "id": "chatcmpl-sync-1",
                  "object": "chat.completion",
                  "created": 1728550000,
                  "model": "gpt-4o",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Sync response"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "total_tokens": 15
                  }
                }
                """;

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", List.of(), false, 0.7);
        ChatCompletionResponse response = client.chatCompletion(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo("chatcmpl-sync-1");
        assertThat(response.choices().get(0).message().content()).isEqualTo("Sync response");
    }
}
