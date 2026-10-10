package com.enterprise.llm.e2e;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.guard.CrossTenantViolationException;
import com.enterprise.llm.guard.SecurityViolationException;
import com.enterprise.llm.guard.ToolAction;
import com.enterprise.llm.guard.ToolActionGatekeeper;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
import com.enterprise.llm.tenant.TenantService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.main.allow-bean-definition-overriding=true"})
class Phase3GovernanceE2ETest {

    private static MockWebServer mockPrimaryServer;
    private static MockWebServer mockFallbackServer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ToolActionGatekeeper toolActionGatekeeper;

    @TestConfiguration
    static class TestConfig {
        @Bean(name = "primaryLlmClient")
        @Primary
        public LlmClient primaryTestLlmClient() {
            String baseUrl = mockPrimaryServer.url("/").toString();
            return new OpenAiCompatibleLlmClient(baseUrl, "test-p3-key");
        }

        @Bean(name = "fallbackLlmClient")
        public LlmClient fallbackTestLlmClient() {
            String baseUrl = mockFallbackServer.url("/").toString();
            return new OpenAiCompatibleLlmClient(baseUrl, "test-p3-fallback");
        }
    }

    @BeforeAll
    static void startServers() throws IOException {
        mockPrimaryServer = new MockWebServer();
        mockPrimaryServer.start();

        mockFallbackServer = new MockWebServer();
        mockFallbackServer.start();
    }

    @AfterAll
    static void stopServers() throws IOException {
        if (mockPrimaryServer != null) {
            mockPrimaryServer.shutdown();
        }
        if (mockFallbackServer != null) {
            mockFallbackServer.shutdown();
        }
    }

    @BeforeEach
    void setupTenants() {
        tenantService.registerTenant(new TenantEntity(
                "tenant-p3-valid", "P3 Standard Corp", "sk-p3-valid-key", 50, 100000, 0, true
        ));
        tenantService.registerTenant(new TenantEntity(
                "tenant-p3-small-quota", "P3 Small Quota", "sk-p3-quota-exhausted", 50, 500, 480, true
        ));
    }

    @Test
    void shouldRejectRequestWhenPreFlightTokenQuotaExceeded() {
        // Tenant daily quota: 500, used: 480.
        // Prompt + default 512 completion estimate requires > 520 tokens -> Exceeds 500!
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "sk-p3-quota-exhausted");
        HttpEntity<String> entity = new HttpEntity<>("""
                {
                  "model": "qwen2.5:0.5b",
                  "messages": [
                    {"role": "user", "content": "Please write a comprehensive summary of quantum physics."}
                  ]
                }
                """, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/chat/completions", entity, String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).contains("Rate limit or daily token quota exceeded");
    }

    @Test
    void shouldSanitizeSensitiveSecretsAndPiiInModelResponse() {
        mockPrimaryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "chat-dlp-1",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "Your credentials are sk-testsecretkey1234567890abcdef and contact 13800138000"
                              }
                            }
                          ],
                          "usage": {"prompt_tokens": 10, "completion_tokens": 15, "total_tokens": 25}
                        }
                        """));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "sk-p3-valid-key");
        HttpEntity<String> entity = new HttpEntity<>("""
                {
                  "model": "qwen2.5:0.5b",
                  "messages": [
                    {"role": "user", "content": "Give me the test credentials"}
                  ]
                }
                """, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/chat/completions", entity, String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Secrets and phone are masked by DLP
        assertThat(response.getBody()).doesNotContain("sk-testsecretkey1234567890abcdef");
        assertThat(response.getBody()).doesNotContain("13800138000");
        assertThat(response.getBody()).contains("[REDACTED_API_KEY]");
        assertThat(response.getBody()).contains("[REDACTED_PHONE]");
    }

    @Test
    void shouldSettleActualTokensInDatabaseAfterSyncExecution() {
        mockPrimaryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "id": "chat-settle-1",
                          "choices": [{"index":0,"message":{"role":"assistant","content":"Done"}}],
                          "usage": {"prompt_tokens": 20, "completion_tokens": 30, "total_tokens": 50}
                        }
                        """));

        long beforeUsed = tenantRepository.findById("tenant-p3-valid").get().getUsedTokens();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "sk-p3-valid-key");
        HttpEntity<String> entity = new HttpEntity<>("""
                {"model": "qwen2.5:0.5b", "messages": [{"role": "user", "content": "ping"}]}
                """, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/v1/chat/completions", entity, String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        long afterUsed = tenantRepository.findById("tenant-p3-valid").get().getUsedTokens();
        assertThat(afterUsed).isEqualTo(beforeUsed + 50);
    }

    @Test
    void shouldBlockCrossTenantToolCallViaGatekeeper() {
        ToolAction crossTenantAction = new ToolAction(
                "query_db",
                Map.of("tenant_id", "victim-tenant", "table", "users")
        );

        assertThatThrownBy(() -> toolActionGatekeeper.validateAction(crossTenantAction, "tenant-p3-valid"))
                .isInstanceOf(CrossTenantViolationException.class)
                .hasMessageContaining("Cross-tenant access violation");
    }

    @Test
    void shouldBlockDangerousDestructiveActionViaGatekeeper() {
        ToolAction dangerousAction = new ToolAction(
                "execute_command",
                Map.of("cmd", "rm -rf /app/data")
        );

        assertThatThrownBy(() -> toolActionGatekeeper.validateAction(dangerousAction, "tenant-p3-valid"))
                .isInstanceOf(SecurityViolationException.class)
                .hasMessageContaining("Dangerous tool execution attempt blocked");
    }
}
