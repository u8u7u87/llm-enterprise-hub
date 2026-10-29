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
 * Proves that shadow traffic declared in YAML is really dispatched, and that a failing shadow call
 * cannot affect the client-visible response.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "llm.shadow.enabled=true",
                "llm.shadow.rules[0].source-model=deepseek-chat",
                "llm.shadow.rules[0].shadow-model=deepseek-reasoner",
                "llm.shadow.rules[0].sample-ratio=1.0"
        })
class ShadowTrafficE2ETest {

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
            return new OpenAiCompatibleLlmClient(mockLlmServer.url("/").toString(), "e2e-shadow-key");
        }

        @Bean(name = "fallbackLlmClient")
        public LlmClient fallbackTestLlmClient() {
            return new OpenAiCompatibleLlmClient(mockLlmServer.url("/").toString(), "e2e-shadow-fallback-key");
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
                "e2e-shadow-tenant", "Shadow Corp", "sk-e2e-shadow-key", 20, 100000, 0, true
        ));
    }

    @Test
    @DisplayName("A sampled request must reach upstream twice, once per model")
    void shouldMirrorToShadowModel() throws InterruptedException {
        enqueueSyncResponse("primary answer", "deepseek-chat");
        enqueueSyncResponse("shadow answer", "deepseek-reasoner");

        ResponseEntity<String> response = post("影子流量测试一");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("primary answer");

        RecordedRequest primary = mockLlmServer.takeRequest(5, TimeUnit.SECONDS);
        RecordedRequest shadow = mockLlmServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(primary).isNotNull();
        assertThat(shadow).isNotNull();
        assertThat(primary.getBody().readUtf8()).contains("\"model\":\"deepseek-chat\"");
        assertThat(shadow.getBody().readUtf8()).contains("\"model\":\"deepseek-reasoner\"");
    }

    @Test
    @DisplayName("A failing shadow call must not break the primary response")
    void shadowFailureMustNotAffectClient() throws InterruptedException {
        enqueueSyncResponse("primary still fine", "deepseek-chat");
        mockLlmServer.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"));

        ResponseEntity<String> response = post("影子流量测试二");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("primary still fine");

        assertThat(mockLlmServer.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(mockLlmServer.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
    }

    private void enqueueSyncResponse(String content, String model) {
        String body = """
                {
                  "id": "shadow-upstream-1",
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

    private ResponseEntity<String> post(String prompt) {
        String requestJson = """
                {
                  "model": "deepseek-chat",
                  "messages": [{"role": "user", "content": "%s"}],
                  "stream": false
                }
                """.formatted(prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "sk-e2e-shadow-key");
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(requestJson, headers),
                String.class);
    }
}
