package com.enterprise.llm.cluster;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.cluster.discovery.ClusterDiscoveryProvider;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RemoteClusterManagerTest {

    private RemoteClusterManager clusterManager;

    @BeforeEach
    void setUp() {
        clusterManager = new RemoteClusterManager();
    }

    @AfterEach
    void tearDown() {
        clusterManager.stop();
    }

    @Test
    void shouldDiscoverNodesAndFilterBySupportedModel() {
        ClusterDiscoveryProvider mockProvider = mock(ClusterDiscoveryProvider.class);
        when(mockProvider.getProviderType()).thenReturn("MOCK");

        UpstreamNode node1 = new UpstreamNode("node-1", "http://10.0.1.1:8000/v1");
        node1.setSupportedModels(Set.of("qwen2.5:72b"));

        UpstreamNode node2 = new UpstreamNode("node-2", "http://10.0.1.2:8000/v1");
        node2.setSupportedModels(Set.of("deepseek-r1"));

        when(mockProvider.discoverNodes("vllm-cluster")).thenReturn(List.of(node1, node2));

        clusterManager.registerDiscoveryProvider(mockProvider);
        clusterManager.addDiscoveryTarget("vllm-cluster");
        clusterManager.refreshTopology();

        assertEquals(2, clusterManager.getActiveNodeCount());

        // Test model routing
        ChatCompletionRequest qwenReq = new ChatCompletionRequest("qwen2.5:72b", List.of(new ChatMessage("user", "hi")), false, 0.7);
        UpstreamNode selectedQwen = clusterManager.selectNode(qwenReq);
        assertNotNull(selectedQwen);
        assertEquals("node-1", selectedQwen.getId());

        ChatCompletionRequest deepseekReq = new ChatCompletionRequest("deepseek-r1", List.of(new ChatMessage("user", "hi")), false, 0.7);
        UpstreamNode selectedDeepseek = clusterManager.selectNode(deepseekReq);
        assertNotNull(selectedDeepseek);
        assertEquals("node-2", selectedDeepseek.getId());

        ChatCompletionRequest unknownReq = new ChatCompletionRequest("unknown-model", List.of(new ChatMessage("user", "hi")), false, 0.7);
        UpstreamNode selectedUnknown = clusterManager.selectNode(unknownReq);
        assertNull(selectedUnknown, "Should return null when no nodes support unknown-model");
    }

    @Test
    void shouldHandleNodeScaleDownAndEviction() {
        ClusterDiscoveryProvider mockProvider = mock(ClusterDiscoveryProvider.class);
        when(mockProvider.getProviderType()).thenReturn("DNS");

        UpstreamNode nodeA = new UpstreamNode("pod-a", "http://10.244.1.1:8000/v1");
        UpstreamNode nodeB = new UpstreamNode("pod-b", "http://10.244.1.2:8000/v1");

        // Cycle 1: 2 pods discovered
        when(mockProvider.discoverNodes("k8s-vllm")).thenReturn(List.of(nodeA, nodeB));
        clusterManager.registerDiscoveryProvider(mockProvider);
        clusterManager.addDiscoveryTarget("k8s-vllm");
        clusterManager.refreshTopology();

        assertEquals(2, clusterManager.getActiveNodeCount());

        // Cycle 2: pod-b terminated / scaled in
        when(mockProvider.discoverNodes("k8s-vllm")).thenReturn(List.of(nodeA));
        clusterManager.refreshTopology();

        assertEquals(1, clusterManager.getActiveNodeCount());
        assertNotNull(clusterManager.selectNode(new ChatCompletionRequest("any", List.of(), false, 0.7)));
        assertEquals("pod-a", clusterManager.getActiveNodes().get(0).getId());
    }

    @Test
    void shouldIntrospectModelCatalogWhenNotSet() {
        ClusterDiscoveryProvider mockProvider = mock(ClusterDiscoveryProvider.class);
        when(mockProvider.getProviderType()).thenReturn("CATALOG");

        UpstreamNode node = new UpstreamNode("node-unconfigured", "http://10.0.0.5:8000/v1");
        when(mockProvider.discoverNodes("vllm-service")).thenReturn(List.of(node));
        when(mockProvider.discoverModels("http://10.0.0.5:8000/v1", "")).thenReturn(List.of("qwen2.5:32b", "qwen2.5:72b"));

        clusterManager.registerDiscoveryProvider(mockProvider);
        clusterManager.addDiscoveryTarget("vllm-service");
        clusterManager.refreshTopology();

        UpstreamNode activeNode = clusterManager.getActiveNodes().get(0);
        assertTrue(activeNode.supportsModel("qwen2.5:32b"));
        assertTrue(activeNode.supportsModel("qwen2.5:72b"));
        assertFalse(activeNode.supportsModel("llama-3.1:70b"));
    }

    @Test
    void shouldSkipUnhealthyOrCircuitOpenNodes() {
        UpstreamNode node1 = new UpstreamNode("node-1", "http://10.0.1.1:8000/v1");
        UpstreamNode node2 = new UpstreamNode("node-2", "http://10.0.1.2:8000/v1");

        clusterManager.registerNode(node1);
        clusterManager.registerNode(node2);

        // Mark node1 unhealthy
        node1.setHealthy(false);

        ChatCompletionRequest req = new ChatCompletionRequest("any", List.of(), false, 0.7);
        UpstreamNode selected = clusterManager.selectNode(req);
        assertEquals("node-2", selected.getId());

        // Now trip circuit breaker on node2
        for (int i = 0; i < 5; i++) {
            node2.recordFailure();
        }
        assertTrue(node2.getCircuitBreaker().isOpen());

        UpstreamNode noneSelected = clusterManager.selectNode(req);
        assertNull(noneSelected, "When all remote nodes are unavailable, selectNode should return null");
    }

    @Test
    void shouldCacheLlmClientInstances() {
        AtomicInteger factoryCalls = new AtomicInteger(0);
        LlmClient mockClient = mock(LlmClient.class);

        RemoteClusterManager customManager = new RemoteClusterManager(
                Collections.emptyList(),
                new LeastRequestsLoadBalancer(),
                node -> {
                    factoryCalls.incrementAndGet();
                    return mockClient;
                }
        );

        UpstreamNode node = new UpstreamNode("node-x", "http://10.0.0.9:8000/v1");
        customManager.registerNode(node);

        LlmClient client1 = customManager.getClientForNode(node);
        LlmClient client2 = customManager.getClientForNode(node);

        assertSame(client1, client2);
        assertEquals(1, factoryCalls.get(), "LlmClient should be cached per node ID");
    }
}
