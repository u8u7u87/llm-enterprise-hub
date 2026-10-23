package com.enterprise.llm.rag.embedding;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingClientTest {

    private MockWebServer mockWebServer;
    private OpenAiCompatibleEmbeddingClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = mockWebServer.url("/v1").toString();
        client = new OpenAiCompatibleEmbeddingClient(baseUrl, "test-api-key", "BAAI/bge-m3");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("Should parse single embedding successfully")
    void shouldEmbedSingleText() {
        String mockResponseBody = """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "embedding",
                      "index": 0,
                      "embedding": [0.123, -0.456, 0.789]
                    }
                  ],
                  "model": "BAAI/bge-m3",
                  "usage": { "prompt_tokens": 5, "total_tokens": 5 }
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody));

        float[] embedding = client.embed("Hello world");
        assertThat(embedding).hasSize(3);
        assertThat(embedding[0]).isEqualTo(0.123f);
        assertThat(embedding[1]).isEqualTo(-0.456f);
        assertThat(embedding[2]).isEqualTo(0.789f);
    }

    @Test
    @DisplayName("Should embed batch texts preserving index order")
    void shouldEmbedBatchTexts() {
        String mockResponseBody = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "index": 1, "embedding": [2.0, 2.1] },
                    { "object": "embedding", "index": 0, "embedding": [1.0, 1.1] }
                  ],
                  "model": "BAAI/bge-m3"
                }
                """;
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody));

        List<float[]> embeddings = client.embedBatch(List.of("First chunk", "Second chunk"));
        assertThat(embeddings).hasSize(2);
        assertThat(embeddings.get(0)).containsExactly(1.0f, 1.1f);
        assertThat(embeddings.get(1)).containsExactly(2.0f, 2.1f);
    }

    @Test
    @DisplayName("Should throw exception when upstream returns 4xx/5xx")
    void shouldHandleUpstreamError() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\": \"Unauthorized\"}"));

        assertThatThrownBy(() -> client.embed("test"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 401");
    }
}
