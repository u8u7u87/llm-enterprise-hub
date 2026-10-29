package com.enterprise.llm.e2e;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a canary rule declared in YAML actually rewrites the model sent upstream, and that a
 * request without a matching rule is forwarded untouched.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "llm.canary.enabled=true",
                "llm.canary.rules[0].base-model=deepseek-chat",
                "llm.canary.rules[0].canary-model=deepseek-reasoner",
                "llm.canary.rules[0].weight-percent=100",
                "llm.canary.rules[0].sticky-by-tenant=true"
        })
class CanaryRoutingE2ETest {

    private static MockWebServer mockLlmServer;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantService tenantService;

    @TestConfiguration
    static class TestConfig {
        @Bean(name = "primaryLlmClient")
        @Primary
        public LlmClient primaryTestLlmClient() {
            return new OpenAiCompatibleLlmClient(mockLlmServer.url("/").toString(), "e2e-canary-key");
        }

        @Bean(name = "fallbackLlmClient")
        public LlmClient fallbackTestLlmClient() {
            return new OpenAiCompatibleLlmClient(mockLlmServer.url("/").toString(), "e2e-canary-fallback-key");
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

    @BeforeEach
    void registerTenant() {
        tenantService.registerTenant(new TenantEntity(
                "e2e-canary-tenant", "Canary Corp", "sk-e2e-canary-key", 20, 100000, 0, true
        ));
    }

    @Test
    @DisplayName("A 100% canary rule must rewrite the model sent upstream")
    void shouldRouteToCanaryModel() throws InterruptedException {
        enqueueSyncResponse("canary-answer", "deepseek-reasoner");

        ResponseEntity<String> response = post("deepseek-chat", "灰度切流测试一");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        RecordedRequest upstream = mockLlmServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream).isNotNull();
        assertThat(upstream.getBody().readUtf8()).contains("\"model\":\"deepseek-reasoner\"");
        assertThat(response.getHeaders().getFirst("X-Hub-Routed-Model")).isEqualTo("deepseek-reasoner");
    }

    @Test
    @DisplayName("A model without a canary rule must be forwarded unchanged")
    void shouldNotRewriteModelWithoutRule() throws InterruptedException {
        enqueueSyncResponse("plain-answer", "qwen-max");

        ResponseEntity<String> response = post("qwen-max", "灰度切流测试二");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        RecordedRequest upstream = mockLlmServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream).isNotNull();
        assertThat(upstream.getBody().readUtf8()).contains("\"model\":\"qwen-max\"");
        assertThat(response.getHeaders().getFirst("X-Hub-Routed-Model")).isNull();
    }

    private void enqueueSyncResponse(String content, String model) {
        String body = """
                {
                  "id": "canary-upstream-1",
                  "object": "chat.completion",
                  "created": 1728550000,
                  "model": "%s",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "%s"}, "finish_reason": "stop"}
                  ],
                  "usage": {"total_tokens": 12}
                }
                """.formatted(model, content);
        mockLlmServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private ResponseEntity<String> post(String model, String prompt) {
        String requestJson = """
                {
                  "model": "%s",
                  "messages": [{"role": "user", "content": "%s"}],
                  "stream": false
                }
                """.formatted(model, prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "sk-e2e-canary-key");
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(requestJson, headers),
                String.class);
    }
}
