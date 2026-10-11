package com.enterprise.llm.e2e;

import com.enterprise.llm.agent.AgentToolOrchestrator;
import com.enterprise.llm.agent.ToolCall;
import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.ToolRegistry;
import com.enterprise.llm.agent.task.AgentTaskEntity;
import com.enterprise.llm.agent.task.AgentTaskManager;
import com.enterprise.llm.agent.task.AgentTaskRepository;
import com.enterprise.llm.agent.task.AgentTaskStatus;
import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.metrics.MetricsRecordService;
import com.enterprise.llm.metrics.SystemMetricPoint;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "admin.secret-key=test-admin-secret-key-12345"
})
class Phase5ResumptionAndDashboardE2ETest {

    private static MockWebServer mockPrimaryServer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    @Autowired
    private AgentTaskManager agentTaskManager;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private MetricsRecordService metricsRecordService;

    @Autowired(required = false)
    private LlmClient primaryLlmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TestConfiguration
    static class TestConfig {
        @Bean(name = "primaryLlmClient")
        @Primary
        public LlmClient primaryTestLlmClient() {
            String baseUrl = mockPrimaryServer.url("/").toString();
            return new OpenAiCompatibleLlmClient(baseUrl, "test-p5-key");
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
        agentTaskRepository.deleteAll();
    }

    @Test
    @DisplayName("Pillar 1: Built-in Web Dashboard loads index.html and root without auth blocks")
    void testWebDashboardStaticAccessE2E() {
        // Access root /
        ResponseEntity<String> rootResp = restTemplate.getForEntity("/", String.class);
        assertThat(rootResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rootResp.getBody()).contains("LLM Enterprise Hub");
        assertThat(rootResp.getBody()).contains("echarts");

        // Access /index.html
        ResponseEntity<String> htmlResp = restTemplate.getForEntity("/index.html", String.class);
        assertThat(htmlResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(htmlResp.getBody()).contains("系统吞吐量与平均延迟趋势");
    }

    @Test
    @DisplayName("Pillar 2: Real-time Metrics recorded and retrieved via timeline API")
    void testMetricsTimelineE2E() {
        // Record test metrics via public record method
        metricsRecordService.record(true, 45, 120, 0, false, false, false);
        metricsRecordService.record(false, 30, 0, 0, false, false, true);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Key", "test-admin-secret-key-12345");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<List<SystemMetricPoint>> resp = restTemplate.exchange(
                "/v1/admin/metrics/timeline?minutes=10",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {}
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).isNotEmpty();
        SystemMetricPoint latest = resp.getBody().get(resp.getBody().size() - 1);
        assertThat(latest.totalRequests()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Pillar 3: Agent Task Interrupt on tool failure -> SUSPENDED with checkpoint -> Resume from checkpoint succeeds via API")
    void testAgentTaskInterruptAndResumeE2E() throws Exception {
        TenantEntity tenant = new TenantEntity("tenant-agent", "Agent Corp", "sk-agent-secret", 100, 50000, 0, true);
        tenantRepository.save(tenant);

        // Register a tool that provides warehouse inventory
        toolRegistry.registerTool("inventory_checker", "Check product inventory", Map.of("sku", "string"), args ->
                "{\"sku\": \"" + args.get("sku") + "\", \"in_stock\": 120, \"warehouse\": \"SH-01\"}"
        );

        // 1. Create a task via AgentTaskManager
        ChatCompletionRequest agentRequest = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(ChatMessage.user("Check inventory for ITEM-999 and answer user")),
                false,
                0.7,
                null,
                toolRegistry.getAllDefinitions().stream().toList(),
                "auto"
        );

        AgentTaskEntity createdTask = agentTaskManager.createTask(agentRequest, "tenant-agent");
        String taskId = createdTask.getTaskId();
        assertThat(taskId).isNotNull();
        assertThat(createdTask.getStatus()).isEqualTo(AgentTaskStatus.PENDING);

        // Turn 1 LLM response: call tool inventory_checker
        ToolCall toolCall = new ToolCall("call_inv_1", "inventory_checker", Map.of("sku", "ITEM-999"));
        String turn1MockBody = objectMapper.writeValueAsString(new ChatCompletionResponse(
                "chatcmpl-agent-1", "chat.completion", 1700000000L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistantWithTools(null, List.of(toolCall)), "tool_calls")),
                new Usage(20, 15, 35)
        ));
        mockPrimaryServer.enqueue(new MockResponse().setResponseCode(200).setBody(turn1MockBody)
                .setHeader("Content-Type", "application/json"));

        // Turn 2: Simulate upstream network disconnection (e.g. 503 Service Unavailable)
        mockPrimaryServer.enqueue(new MockResponse().setResponseCode(503).setBody("Service Unavailable"));

        // Execute task -> should encounter 503 and suspend
        assertThatThrownBy(() -> agentTaskManager.executeTask(taskId, primaryLlmClient))
                .isInstanceOf(RuntimeException.class);

        // Verify task state in database: SUSPENDED, iteration preserved, history contains tool call and output!
        AgentTaskEntity suspendedTask = agentTaskRepository.findById(taskId).orElseThrow();
        assertThat(suspendedTask.getStatus()).isEqualTo(AgentTaskStatus.SUSPENDED);
        assertThat(suspendedTask.getCurrentIteration()).isEqualTo(1);
        assertThat(suspendedTask.getHistoryJson()).contains("call_inv_1");
        assertThat(suspendedTask.getHistoryJson()).contains("inventory_checker");
        assertThat(suspendedTask.getHistoryJson()).contains("SH-01"); // Tool output was checkpointed!
        assertThat(suspendedTask.getErrorMessage()).contains("503");

        // 2. Query task via GET /v1/agent/tasks and GET /v1/agent/tasks/{id}
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Admin-Key", "test-admin-secret-key-12345");
        HttpEntity<Void> getEntity = new HttpEntity<>(headers);

        ResponseEntity<List<AgentTaskEntity>> listResp = restTemplate.exchange(
                "/v1/agent/tasks",
                HttpMethod.GET,
                getEntity,
                new ParameterizedTypeReference<>() {}
        );
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).anyMatch(t -> t.getTaskId().equals(taskId) && t.getStatus() == AgentTaskStatus.SUSPENDED);

        ResponseEntity<AgentTaskEntity> getOneResp = restTemplate.exchange(
                "/v1/agent/tasks/" + taskId,
                HttpMethod.GET,
                getEntity,
                AgentTaskEntity.class
        );
        assertThat(getOneResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getOneResp.getBody().getStatus()).isEqualTo(AgentTaskStatus.SUSPENDED);

        // 3. Resume task via POST /v1/agent/tasks/{id}/resume after network recovers
        String turn2MockBody = objectMapper.writeValueAsString(new ChatCompletionResponse(
                "chatcmpl-agent-2", "chat.completion", 1700000005L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Item ITEM-999 is in stock with 120 units at warehouse SH-01."), "stop")),
                new Usage(30, 20, 50)
        ));
        mockPrimaryServer.enqueue(new MockResponse().setResponseCode(200).setBody(turn2MockBody)
                .setHeader("Content-Type", "application/json"));

        ResponseEntity<ChatCompletionResponse> resumeResp = restTemplate.postForEntity(
                "/v1/agent/tasks/" + taskId + "/resume",
                getEntity,
                ChatCompletionResponse.class
        );

        assertThat(resumeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatCompletionResponse finalResponse = resumeResp.getBody();
        assertThat(finalResponse).isNotNull();
        assertThat(finalResponse.choices().get(0).message().content())
                .isEqualTo("Item ITEM-999 is in stock with 120 units at warehouse SH-01.");

        // 4. Verify DB state is now COMPLETED
        AgentTaskEntity completedTask = agentTaskRepository.findById(taskId).orElseThrow();
        assertThat(completedTask.getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(completedTask.getRetryCount()).isEqualTo(1);
        assertThat(completedTask.getErrorMessage()).isNull();
    }
}
