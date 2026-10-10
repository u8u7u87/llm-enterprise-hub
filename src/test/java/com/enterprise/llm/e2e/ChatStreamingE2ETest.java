package com.enterprise.llm.e2e;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamingE2ETest {

    private static MockWebServer mockLlmServer;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public LlmClient testLlmClient() {
            String baseUrl = mockLlmServer.url("/").toString();
            return new OpenAiCompatibleLlmClient(baseUrl, "e2e-test-key");
        }
    }

    @BeforeAll
    static void startServer() throws IOException {
        mockLlmServer = new MockWebServer();
        mockLlmServer.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        mockLlmServer.shutdown();
    }

    @Test
    void shouldExecuteSyncCompletionEndToEnd() {
        String upstreamResponse = """
                {
                  "id": "e2e-sync-1",
                  "object": "chat.completion",
                  "created": 1728550000,
                  "model": "deepseek-chat",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Hello from DeepSeek"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "total_tokens": 42
                  }
                }
                """;
        mockLlmServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(upstreamResponse));

        String requestJson = """
                {
                  "model": "deepseek-chat",
                  "messages": [{"role": "user", "content": "Hello!"}],
                  "stream": false
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                entity,
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("Hello from DeepSeek");
        assertThat(response.getBody()).contains("\"model\":\"deepseek-chat\"");
    }

    @Test
    void shouldExecuteStreamingCompletionEndToEnd() {
        String ssePayload = """
                data: {"id":"e2e-1","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"Hi "}}]}
                
                data: {"id":"e2e-2","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"enterprise!"}}]}
                
                data: [DONE]
                
                """;

        mockLlmServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(ssePayload));

        String requestJson = """
                {
                  "model": "deepseek-chat",
                  "messages": [{"role": "user", "content": "Stream test"}],
                  "stream": true
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                entity,
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType().toString()).contains("text/event-stream");
        assertThat(response.getBody()).contains("data:");
        assertThat(response.getBody()).contains("enterprise!");
        assertThat(response.getBody()).contains("[DONE]");
    }
}
