package com.enterprise.llm.e2e;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.store.VectorStore;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
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
import java.util.List;
import java.util.Map;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "rag.enabled=true",
        "rag.top-k=3",
        "rag.min-score=0.5",
        "milvus.endpoint=disabled"
})
class Phase6MilvusRagE2ETest {

    private static MockWebServer mockPrimaryServer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private VectorStore vectorStore;

    private static final EmbeddingClient mockEmbeddingClient = mock(EmbeddingClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class TestConfig {
        @Bean(name = "primaryLlmClient")
        @Primary
        public LlmClient primaryTestLlmClient() {
            String baseUrl = mockPrimaryServer.url("/").toString();
            return new OpenAiCompatibleLlmClient(baseUrl, "test-p6-key");
        }

        @Bean(name = "fallbackLlmClient")
        public LlmClient fallbackTestLlmClient() {
            String baseUrl = mockPrimaryServer.url("/").toString();
            return new OpenAiCompatibleLlmClient(baseUrl, "test-p6-fallback");
        }

        @Bean
        @Primary
        public EmbeddingClient testEmbeddingClient() {
            return mockEmbeddingClient;
        }
    }

    @BeforeAll
    static void startServers() throws IOException {
        mockPrimaryServer = new MockWebServer();
        mockPrimaryServer.start();
    }

    @AfterAll
    static void stopServers() throws IOException {
        if (mockPrimaryServer != null) {
            mockPrimaryServer.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        tenantRepository.deleteAll();
    }

    @Test
    @DisplayName("Pillar 1 & 2: Ingest knowledge doc -> User query automatically augmented with retrieved chunks strictly isolated by tenant")
    void testEndToEndRagIngestionAndChatAugmentation() throws Exception {
        // 1. Setup Tenant A and Tenant B
        TenantEntity tenantA = new TenantEntity("tenant-alpha", "Alpha Corp", "sk-alpha-secret", 100, 50000, 0, true);
        TenantEntity tenantB = new TenantEntity("tenant-beta", "Beta Corp", "sk-beta-secret", 100, 50000, 0, true);
        tenantRepository.save(tenantA);
        tenantRepository.save(tenantB);

        // Fixed mock embedding vector for predictable testing
        float[] vector = new float[]{1.0f, 0.0f, 0.0f};
        when(mockEmbeddingClient.embed(any())).thenReturn(vector);
        when(mockEmbeddingClient.embedBatch(anyList())).thenReturn(List.of(vector));

        // 2. Tenant A ingests confidential doc
        HttpHeaders headersA = new HttpHeaders();
        headersA.setBearerAuth("sk-alpha-secret");
        headersA.setContentType(MediaType.APPLICATION_JSON);

        String ingestPayloadA = """
                {
                  "docName": "alpha_project.md",
                  "content": "Project Alpha code name is Aurora, launched in Q4 with 20 team members."
                }
                """;

        ResponseEntity<Map> ingestRespA = restTemplate.postForEntity(
                "/v1/knowledge/documents",
                new HttpEntity<>(ingestPayloadA, headersA),
                Map.class
        );
        assertThat(ingestRespA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ingestRespA.getBody().get("status")).isEqualTo("success");

        // 3. Tenant A issues a chat completion query: "What is Project Alpha code name?"
        String mockLlmResponseBody = objectMapper.writeValueAsString(new ChatCompletionResponse(
                "chatcmpl-rag-1", "chat.completion", 1700000000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Project Alpha's code name is Aurora."), "stop")),
                new Usage(45, 10, 55)
        ));
        mockPrimaryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockLlmResponseBody));

        ChatCompletionRequest chatRequestA = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("What is Project Alpha code name?")),
                false,
                0.7
        );

        ResponseEntity<ChatCompletionResponse> chatRespA = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(chatRequestA, headersA),
                ChatCompletionResponse.class
        );

        assertThat(chatRespA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(chatRespA.getBody().choices().get(0).message().content())
                .isEqualTo("Project Alpha's code name is Aurora.");

        // 4. Verify upstream LLM received the augmented prompt containing the knowledge chunk
        RecordedRequest recordedReqA = mockPrimaryServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recordedReqA).isNotNull();
        String sentBodyA = recordedReqA.getBody().readUtf8();
        assertThat(sentBodyA).contains("【相关知识库参考资料】");
        assertThat(sentBodyA).contains("Aurora");
        assertThat(sentBodyA).contains("alpha_project.md");

        // 5. Cross-tenant isolation verification: Tenant B asks the same question
        // Tenant B should NOT retrieve Tenant A's doc!
        mockPrimaryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockLlmResponseBody));

        HttpHeaders headersB = new HttpHeaders();
        headersB.setBearerAuth("sk-beta-secret");
        headersB.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ChatCompletionResponse> chatRespB = restTemplate.postForEntity(
                "/v1/chat/completions",
                new HttpEntity<>(chatRequestA, headersB),
                ChatCompletionResponse.class
        );
        assertThat(chatRespB.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The request sent to LLM for Tenant B should NOT contain Aurora or Tenant A's doc
        RecordedRequest recordedReqB = mockPrimaryServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recordedReqB).isNotNull();
        String sentBodyB = recordedReqB.getBody().readUtf8();
        assertThat(sentBodyB).doesNotContain("Aurora");
        assertThat(sentBodyB).doesNotContain("alpha_project.md");
    }
}
