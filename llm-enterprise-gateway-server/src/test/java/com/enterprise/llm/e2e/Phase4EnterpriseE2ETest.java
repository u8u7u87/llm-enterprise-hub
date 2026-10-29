package com.enterprise.llm.e2e;

import com.enterprise.llm.admin.AdminAnalyticsOverview;
import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "admin.secret-key=test-admin-secret-key-12345"
})
class Phase4EnterpriseE2ETest {

    private static MockWebServer mockPrimaryServer;
    private static MockWebServer mockFallbackServer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ToolRegistry toolRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class TestConfig {
        @Bean(name = "primaryLlmClient")
        @Primary
        public LlmClient primaryTestLlmClient() {
            String baseUrl = mockPrimaryServer.url("/").toString();
            return new OpenAiCompatibleLlmClient(baseUrl, "test-p4-key");
        }

        @Bean(name = "fallbackLlmClient")
        public LlmClient fallbackTestLlmClient() {
            String baseUrl = mockFallbackServer.url("/").toString();
            return new OpenAiCompatibleLlmClient(baseUrl, "test-p4-fallback");
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
    void setUp() {
        tenantRepository.deleteAll();
    }

    @Test
    @DisplayName("Pillar 1: Two-Level Cache hits return X-Cache-Lookup: HIT without depleting tenant quota")
    void testTwoLevelCacheE2E() throws Exception {
        TenantEntity tenant = new TenantEntity("cache-tenant", "Cache Corp", "sk-cache-secret", 100, 50000, 0, true);
        tenantRepository.save(tenant);

        String mockResponseBody = """
                {
                  "id": "chatcmpl-cache-1",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "qwen2.5:0.5b",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": "The capital of France is Paris." },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": { "prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20 }
                }
                """;
        mockPrimaryServer.enqueue(new MockResponse().setResponseCode(200).setBody(mockResponseBody)
                .setHeader("Content-Type", "application/json"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("sk-cache-secret");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ChatCompletionRequest request1 = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("What is the capital of France?")),
                false,
                0.7
        );

        // 1st request: Cache MISS -> upstream LLM called
        ResponseEntity<ChatCompletionResponse> resp1 = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(request1, headers),
                ChatCompletionResponse.class
        );
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp1.getHeaders().getFirst("X-Cache-Lookup")).isNull();
        assertThat(resp1.getBody().choices().get(0).message().content()).isEqualTo("The capital of France is Paris.");

        TenantEntity afterReq1 = tenantRepository.findById("cache-tenant").orElseThrow();
        assertThat(afterReq1.getUsedTokens()).isEqualTo(20);

        // 2nd request: Exact Match -> L1 Cache HIT -> 0 tokens deducted
        ResponseEntity<ChatCompletionResponse> resp2 = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(request1, headers),
                ChatCompletionResponse.class
        );
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp2.getHeaders().getFirst("X-Cache-Lookup")).isEqualTo("HIT");
        assertThat(resp2.getBody().choices().get(0).message().content()).isEqualTo("The capital of France is Paris.");

        TenantEntity afterReq2 = tenantRepository.findById("cache-tenant").orElseThrow();
        assertThat(afterReq2.getUsedTokens()).isEqualTo(20); // Token unchanged!
    }

    @Test
    @DisplayName("Pillar 2: Admin Controller REST operations and Operational Analytics overview")
    void testAdminControlPlaneE2E() {
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("X-Admin-Key", "test-admin-secret-key-12345");
        adminHeaders.setContentType(MediaType.APPLICATION_JSON);

        // 1. Create a new tenant via Admin API
        TenantEntity newTenant = new TenantEntity("admin-created-tenant", "FinTech Inc", "sk-fintech-key", 50, 100000, 0, true);
        ResponseEntity<TenantEntity> createResp = restTemplate.postForEntity(
                "/v1/admin/tenants",
                new HttpEntity<>(newTenant, adminHeaders),
                TenantEntity.class
        );
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createResp.getBody().getId()).isEqualTo("admin-created-tenant");

        // 2. Query tenant list
        ResponseEntity<List<TenantEntity>> listResp = restTemplate.exchange(
                "/v1/admin/tenants",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<List<TenantEntity>>() {}
        );
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).extracting(TenantEntity::getId).contains("admin-created-tenant");

        // 3. Recharge quota
        ResponseEntity<Map> rechargeResp = restTemplate.exchange(
                "/v1/admin/tenants/admin-created-tenant/quota",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("daily_token_quota", 250000), adminHeaders),
                Map.class
        );
        assertThat(rechargeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        TenantEntity updated = tenantRepository.findById("admin-created-tenant").orElseThrow();
        assertThat(updated.getDailyTokenQuota()).isEqualTo(250000);

        // 4. Toggle tenant active status
        ResponseEntity<Map> toggleResp = restTemplate.exchange(
                "/v1/admin/tenants/admin-created-tenant/status",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("enabled", false), adminHeaders),
                Map.class
        );
        assertThat(toggleResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tenantRepository.findById("admin-created-tenant").orElseThrow().isEnabled()).isFalse();

        // 5. Get Analytics Overview
        ResponseEntity<AdminAnalyticsOverview> analyticsResp = restTemplate.exchange(
                "/v1/admin/analytics/overview",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                AdminAnalyticsOverview.class
        );
        assertThat(analyticsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analyticsResp.getBody()).isNotNull();
        assertThat(analyticsResp.getBody().totalTenants()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Pillar 3: Agent Tool Orchestration with ReAct Multi-Round Execution & Gatekeeper Protection")
    void testAgentToolCallingE2E() throws Exception {
        TenantEntity tenant = new TenantEntity("agent-tenant", "Agent Corp", "sk-agent-secret", 100, 50000, 0, true);
        tenantRepository.save(tenant);

        // Register tools in sandbox
        AtomicInteger toolExecutions = new AtomicInteger(0);
        toolRegistry.registerTool(
                "query_db",
                "Query database records",
                Map.of("type", "object"),
                args -> {
                    toolExecutions.incrementAndGet();
                    return "Result: User 101 balance is $5,000";
                }
        );

        // Model responses
        // Turn 1: Model requests tool call
        ToolCall toolCall = new ToolCall("call_db_1", "query_db", Map.of("user_id", "101"));
        String turn1Response = objectMapper.writeValueAsString(new ChatCompletionResponse(
                "turn-1", "chat.completion", 1000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, List.of(toolCall)), "tool_calls")),
                new Usage(15, 10, 25)
        ));

        // Turn 2: Model outputs final answer
        String turn2Response = objectMapper.writeValueAsString(new ChatCompletionResponse(
                "turn-2", "chat.completion", 1001L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("User 101 has a current balance of $5,000."), "stop")),
                new Usage(35, 12, 47)
        ));

        mockPrimaryServer.enqueue(new MockResponse().setResponseCode(200).setBody(turn1Response).setHeader("Content-Type", "application/json"));
        mockPrimaryServer.enqueue(new MockResponse().setResponseCode(200).setBody(turn2Response).setHeader("Content-Type", "application/json"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("sk-agent-secret");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ChatCompletionRequest agentRequest = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("What is user 101's balance?")),
                false,
                0.7,
                null,
                toolRegistry.getAllDefinitions().stream().toList(),
                "auto"
        );

        ResponseEntity<ChatCompletionResponse> response = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(agentRequest, headers),
                ChatCompletionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().choices().get(0).message().content())
                .isEqualTo("User 101 has a current balance of $5,000.");
        assertThat(toolExecutions.get()).isEqualTo(1);
    }
}
