package com.enterprise.llm.cluster;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.cluster.discovery.StaticClusterDiscoveryProvider;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.service.ChatExecutionService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteClusterDiscoveryIntegrationTest {

    private MockWebServer vllmWorker1;
    private MockWebServer vllmWorker2;
    private MockWebServer localOllamaFallback;

    private LlmClient primaryClient;
    private LlmClient fallbackClient;
    private RemoteClusterManager clusterManager;
    private FailoverRoutingService routingService;
    private ChatExecutionService executionService;

    @BeforeEach
    void setUp() throws IOException {
        vllmWorker1 = new MockWebServer();
        vllmWorker1.start();

        vllmWorker2 = new MockWebServer();
        vllmWorker2.start();

        localOllamaFallback = new MockWebServer();
        localOllamaFallback.start();

        primaryClient = new OpenAiCompatibleLlmClient("http://localhost:11434/v1", "ollama");
        fallbackClient = new OpenAiCompatibleLlmClient(localOllamaFallback.url("/v1").toString(), "ollama");

        // Set up cluster manager with static discovery pointing to the 2 mock vLLM workers
        String clusterEndpoints = vllmWorker1.url("/v1").toString() + "," + vllmWorker2.url("/v1").toString();
        clusterManager = new RemoteClusterManager(
                List.of(new StaticClusterDiscoveryProvider()),
                new LeastRequestsLoadBalancer()
        );
        clusterManager.addDiscoveryTarget(clusterEndpoints);
        clusterManager.refreshTopology();

        routingService = new FailoverRoutingService(
                primaryClient,
                fallbackClient,
                "qwen2.5:0.5b",
                null,
                null,
                clusterManager
        );

        executionService = new ChatExecutionService(
                routingService,
                null,
                null,
                null
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        clusterManager.stop();
        vllmWorker1.shutdown();
        vllmWorker2.shutdown();
        localOllamaFallback.shutdown();
    }

    @Test
    void shouldDiscoverRemoteNodesAndRouteWithLeastRequests() {
        // vLLM Worker 1 responds
        vllmWorker1.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"vllm-1","model":"qwen2.5:72b","choices":[{"message":{"content":"Hello from vLLM Worker 1"}}]}
                        """));

        // vLLM Worker 2 responds
        vllmWorker2.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"vllm-2","model":"qwen2.5:72b","choices":[{"message":{"content":"Hello from vLLM Worker 2"}}]}
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:72b",
                List.of(new ChatMessage("user", "Hello vLLM Cluster")),
                false,
                0.7
        );

        // First request routes to one of the workers
        ChatCompletionResponse resp1 = executionService.executeSync(request);
        assertThat(resp1).isNotNull();
        assertThat(resp1.choices().get(0).message().content()).startsWith("Hello from vLLM Worker");

        // Second request
        ChatCompletionResponse resp2 = executionService.executeSync(request);
        assertThat(resp2).isNotNull();
        assertThat(resp2.choices().get(0).message().content()).startsWith("Hello from vLLM Worker");

        // Local fallback should have 0 requests because remote cluster is healthy
        assertThat(localOllamaFallback.getRequestCount()).isEqualTo(0);
    }

    @Test
    void shouldFallbackToLocalWhenAllRemoteNodesFail() {
        // Worker 1 and Worker 2 fail with 500
        vllmWorker1.enqueue(new MockResponse().setResponseCode(500));
        vllmWorker2.enqueue(new MockResponse().setResponseCode(500));

        // Local fallback responds successfully
        localOllamaFallback.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"local-1","model":"qwen2.5:0.5b","choices":[{"message":{"content":"Hello from Local Ollama Safety Net"}}]}
                        """));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:72b",
                List.of(new ChatMessage("user", "Hello Cluster")),
                false,
                0.7
        );

        ChatCompletionResponse response = executionService.executeSync(request);
        assertThat(response).isNotNull();
        assertThat(response.choices().get(0).message().content()).isEqualTo("Hello from Local Ollama Safety Net");
        assertThat(localOllamaFallback.getRequestCount()).isEqualTo(1);
    }
}
