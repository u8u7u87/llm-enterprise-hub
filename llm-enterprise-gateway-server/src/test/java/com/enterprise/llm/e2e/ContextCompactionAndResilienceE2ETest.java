package com.enterprise.llm.e2e;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "llm.context.max-context-tokens=200",
        "llm.context.keep-last-messages=2",
        "llm.context.tool-pruning-enabled=true",
        "llm.resilience.backoff-base-ms=10",
        "rag.enabled=false"
})
class ContextCompactionAndResilienceE2ETest {

    private static MockWebServer mockPrimaryServer;
    private static MockWebServer mockFallbackServer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class TestConfig {
        @Bean("primaryLlmClient")
        @Primary
        public LlmClient primaryLlmClient() {
            return new OpenAiCompatibleLlmClient(mockPrimaryServer.url("/").toString(), "test-key");
        }

        @Bean("fallbackLlmClient")
        public LlmClient fallbackLlmClient() {
            return new OpenAiCompatibleLlmClient(mockFallbackServer.url("/").toString(), "fallback-key");
        }
    }

    @BeforeAll
    static void startMockServers() throws IOException {
        mockPrimaryServer = new MockWebServer();
        mockPrimaryServer.start();

        mockFallbackServer = new MockWebServer();
        mockFallbackServer.start();
    }

    @AfterAll
    static void stopMockServers() throws IOException {
        mockPrimaryServer.shutdown();
        mockFallbackServer.shutdown();
    }

    @BeforeEach
    void setUpTenant() {
        tenantRepository.save(new TenantEntity("t-resilience", "Resilience Tenant", "key-resilience", 1000, 1000000, 0, true));
    }

    @Test
    @DisplayName("E2E: Context distillation & tool pruning occurs when context exceeds budget")
    void testProactiveContextCompaction() throws Exception {
        mockPrimaryServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "chatcmpl-proactive",
                          "model": "gpt-4o",
                          "choices": [{"message": {"role": "assistant", "content": "Context trimmed successfully"}}]
                        }
                        """));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("System instructions"));
        // Old turn with verbose tool result
        messages.add(ChatMessage.user("Query old database records"));
        messages.add(ChatMessage.assistant("Invoking query db..."));
        messages.add(ChatMessage.tool("tool_1", "{\"huge_dataset\": [\"" + "x".repeat(600) + "\"]}"));
        // More turns
        for (int i = 0; i < 5; i++) {
            messages.add(ChatMessage.user("User message " + i));
            messages.add(ChatMessage.assistant("Assistant message " + i));
        }
        messages.add(ChatMessage.user("Latest user query"));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", messages, false, 0.7);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "key-resilience");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        RecordedRequest recorded = mockPrimaryServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        String sentBody = recorded.getBody().readUtf8();

        // Verify tool message was pruned and old turns were distilled
        assertThat(sentBody).contains("[Earlier tool output pruned for context token efficiency");
        assertThat(sentBody).contains("【前序会话历史提炼摘要与背景备忘】");
        assertThat(sentBody).contains("Latest user query");
    }

    @Test
    @DisplayName("E2E: Auto emergency compaction and in-place recovery on 400 Context Length Exceeded")
    void testContextLengthExceededSelfHealing() throws Exception {
        // Attempt 1: Upstream rejects with 400 context_length_exceeded
        mockPrimaryServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "error": {
                            "message": "Maximum context length exceeded: please reduce message length",
                            "type": "invalid_request_error",
                            "code": "context_length_exceeded"
                          }
                        }
                        """));

        // Attempt 2: After auto emergency compaction, upstream accepts
        mockPrimaryServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "chatcmpl-selfheal",
                          "model": "gpt-4o",
                          "choices": [{"message": {"role": "assistant", "content": "Emergency compacted and succeeded!"}}]
                        }
                        """));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("System instructions"));
        for (int i = 0; i < 8; i++) {
            messages.add(ChatMessage.user("Topic " + i + " detailed discussion"));
            messages.add(ChatMessage.assistant("Response " + i + " with detailed analysis"));
        }
        messages.add(ChatMessage.user("Current conclusion"));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", messages, false, 0.7);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "key-resilience");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Emergency compacted and succeeded!");
        // Two requests were sent to primary
        assertThat(mockPrimaryServer.getRequestCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("E2E: 429 Rate limit retry with backoff")
    void testRateLimitBackoffRetry() {
        mockPrimaryServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "0")
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "error": {
                            "message": "Rate limit exceeded",
                            "type": "requests",
                            "code": "rate_limit_exceeded"
                          }
                        }
                        """));

        mockPrimaryServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "chatcmpl-raterecovered",
                          "model": "gpt-4o",
                          "choices": [{"message": {"role": "assistant", "content": "Backoff recovery success"}}]
                        }
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", List.of(ChatMessage.user("Hello")), false, 0.7);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "key-resilience");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Backoff recovery success");
    }

    @Test
    @DisplayName("E2E: Content filter returns standard OpenAI error JSON and does not failover")
    void testContentFilterFastFail() throws Exception {
        mockPrimaryServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "error": {
                            "message": "Safety policy violation detected",
                            "type": "invalid_request_error",
                            "code": "content_filter"
                          }
                        }
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", List.of(ChatMessage.user("Dangerous prompt")), false, 0.7);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "key-resilience");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("error")).isTrue();
        assertThat(json.path("error").path("code").asText()).isEqualTo("content_filter");
        assertThat(json.path("error").path("message").asText()).contains("Safety policy violation");
    }
}
