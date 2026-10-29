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
 * Proves the two-way privacy vault is really in the request path: the upstream model never sees the
 * user's phone number, and the client still receives it in clear text.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "llm.privacy.enabled=true"
        })
class PrivacyVaultE2ETest {

    private static final String PHONE = "13800138000";

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
            return new OpenAiCompatibleLlmClient(mockLlmServer.url("/").toString(), "e2e-privacy-key");
        }

        @Bean(name = "fallbackLlmClient")
        public LlmClient fallbackTestLlmClient() {
            return new OpenAiCompatibleLlmClient(mockLlmServer.url("/").toString(), "e2e-privacy-fallback-key");
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
                "e2e-privacy-tenant", "Privacy Corp", "sk-e2e-privacy-key", 20, 100000, 0, true
        ));
    }

    @Test
    @DisplayName("Upstream must see a placeholder, the client must see the real number")
    void syncPathMustPseudonymiseInboundAndRestoreOutbound() throws InterruptedException {
        enqueueSyncResponse("好的，已记录 <PHONE_1>", "deepseek-chat");

        ResponseEntity<String> response = postSync("请记录我的手机号 13800138000，谢谢");

        RecordedRequest upstream = mockLlmServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(upstream).isNotNull();
        String upstreamBody = upstream.getBody().readUtf8();
        assertThat(upstreamBody).doesNotContain(PHONE);
        assertThat(upstreamBody).contains("<PHONE_1>");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains(PHONE);
        assertThat(response.getBody()).doesNotContain("<PHONE_1>");
    }

    @Test
    @DisplayName("A prompt carrying PII must bypass the response cache")
    void piiRequestMustNotBeServedFromCache() throws InterruptedException {
        enqueueSyncResponse("第一次回答 <PHONE_1>", "deepseek-chat");
        enqueueSyncResponse("第二次回答 <PHONE_1>", "deepseek-chat");

        String prompt = "再记一次 13800138000，这是同一条消息";
        postSync(prompt);
        postSync(prompt);

        assertThat(mockLlmServer.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(mockLlmServer.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    @DisplayName("A placeholder split across two chunks must still be restored")
    void streamingPathMustRestoreSplitPlaceholder() {
        String ssePayload = """
                data: {"id":"p1","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"好的，已记录 <PHO"}}]}
                
                data: {"id":"p2","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"NE_1> ，请确认"}}]}
                
                data: [DONE]
                
                """;
        mockLlmServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(ssePayload));

        ResponseEntity<String> response = postStream("请确认我的手机号 13800138000 是否正确");

        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains(PHONE);
        assertThat(body).doesNotContain("<PHONE_1>");
        assertThat(body).contains("[DONE]");
    }

    private void enqueueSyncResponse(String content, String model) {
        String body = """
                {
                  "id": "privacy-upstream-1",
                  "object": "chat.completion",
                  "created": 1728550000,
                  "model": "%s",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "%s"}, "finish_reason": "stop"}
                  ],
                  "usage": {"total_tokens": 18}
                }
                """.formatted(model, content);
        mockLlmServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private ResponseEntity<String> postSync(String prompt) {
        return post(prompt, false);
    }

    private ResponseEntity<String> postStream(String prompt) {
        return post(prompt, true);
    }

    private ResponseEntity<String> post(String prompt, boolean stream) {
        String requestJson = """
                {
                  "model": "deepseek-chat",
                  "messages": [{"role": "user", "content": "%s"}],
                  "stream": %s
                }
                """.formatted(prompt, stream);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "sk-e2e-privacy-key");
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(requestJson, headers),
                String.class);
    }
}
