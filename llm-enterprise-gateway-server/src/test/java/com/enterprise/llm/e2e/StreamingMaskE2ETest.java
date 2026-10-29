package com.enterprise.llm.e2e;

import java.io.IOException;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that the streaming output guardrail in MASK mode actually rewrites the bytes
 * sent to the client, including when a phone number is split across two upstream chunks.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.allow-bean-definition-overriding=true",
                "llm.guardrail.streaming.mode=MASK"
        })
class StreamingMaskE2ETest {

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
            return new OpenAiCompatibleLlmClient(mockLlmServer.url("/").toString(), "e2e-mask-key");
        }

        @Bean(name = "fallbackLlmClient")
        public LlmClient fallbackTestLlmClient() {
            return new OpenAiCompatibleLlmClient(mockLlmServer.url("/").toString(), "e2e-mask-fallback-key");
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
                "e2e-mask-tenant", "Mask Corp", "sk-e2e-mask-key", 20, 100000, 0, true
        ));
    }

    @Test
    @DisplayName("A phone number split across two chunks must reach the client masked")
    void shouldMaskPhoneNumberSplitAcrossChunks() {
        String ssePayload = """
                data: {"id":"m1","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"联系电话138000"}}]}
                
                data: {"id":"m2","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"00000，谢谢"}}]}
                
                data: [DONE]
                
                """;
        mockLlmServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(ssePayload));

        ResponseEntity<String> response = postStream("流式脱敏测试一");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("1**********");
        assertThat(response.getBody()).doesNotContain(PHONE);
        assertThat(response.getBody()).contains("[DONE]");
    }

    @Test
    @DisplayName("Text held back at the end of a stream must be flushed before [DONE]")
    void shouldFlushHeldBackTailBeforeDone() {
        String ssePayload = """
                data: {"id":"f1","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"订单号 138"}}]}
                
                data: [DONE]
                
                """;
        mockLlmServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(ssePayload));

        ResponseEntity<String> response = postStream("流式脱敏测试二");

        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("订单号");
        assertThat(body).contains("138");
        assertThat(body.indexOf("138")).isLessThan(body.indexOf("[DONE]"));
    }

    private ResponseEntity<String> postStream(String prompt) {
        String requestJson = """
                {
                  "model": "deepseek-chat",
                  "messages": [{"role": "user", "content": "%s"}],
                  "stream": true
                }
                """.formatted(prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "sk-e2e-mask-key");
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/chat/completions",
                new HttpEntity<>(requestJson, headers),
                String.class);
    }
}
