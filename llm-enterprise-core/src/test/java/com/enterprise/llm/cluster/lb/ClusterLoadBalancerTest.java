package com.enterprise.llm.cluster.lb;

import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClusterLoadBalancerTest {

    @Test
    @DisplayName("LeastRequestsLoadBalancer selects minimum active requests node and dynamically adapts")
    void testLeastRequestsLoadBalancer() {
        UpstreamNode node1 = new UpstreamNode("node-1", "http://node1:8000");
        UpstreamNode node2 = new UpstreamNode("node-2", "http://node2:8000");
        UpstreamNode node3 = new UpstreamNode("node-3", "http://node3:8000");

        node1.setActiveRequests(5);
        node2.setActiveRequests(2);
        node3.setActiveRequests(8);

        List<UpstreamNode> nodes = List.of(node1, node2, node3);
        LeastRequestsLoadBalancer lb = new LeastRequestsLoadBalancer();
        ChatCompletionRequest request = new ChatCompletionRequest("qwen-max", List.of(ChatMessage.user("hello")), false, 0.7);

        // Active requests: 5, 2, 8 -> selects node-2
        UpstreamNode selected1 = lb.choose(nodes, request);
        assertNotNull(selected1);
        assertEquals("node-2", selected1.getId());

        // When node-2 active requests increases to 6 (5, 6, 8) -> selects node-1
        node2.setActiveRequests(6);
        UpstreamNode selected2 = lb.choose(nodes, request);
        assertNotNull(selected2);
        assertEquals("node-1", selected2.getId());

        // When node-1 also increases to 8 (8, 6, 8) -> selects node-2
        node1.setActiveRequests(8);
        UpstreamNode selected3 = lb.choose(nodes, request);
        assertNotNull(selected3);
        assertEquals("node-2", selected3.getId());
    }

    @Test
    @DisplayName("LeastRequestsLoadBalancer breaks ties using round-robin")
    void testLeastRequestsTieBreaking() {
        UpstreamNode nodeA = new UpstreamNode("node-a", "http://node-a:8000");
        UpstreamNode nodeB = new UpstreamNode("node-b", "http://node-b:8000");
        nodeA.setActiveRequests(3);
        nodeB.setActiveRequests(3);

        LeastRequestsLoadBalancer lb = new LeastRequestsLoadBalancer();
        ChatCompletionRequest request = new ChatCompletionRequest("qwen-max", List.of(ChatMessage.user("ping")), false, 0.7);

        UpstreamNode first = lb.choose(List.of(nodeA, nodeB), request);
        UpstreamNode second = lb.choose(List.of(nodeA, nodeB), request);

        assertNotEquals(first.getId(), second.getId(), "Tied nodes should alternate under round-robin tie breaker");
    }

    @Test
    @DisplayName("PrefixHashLoadBalancer consistently maps identical prompt prefixes to the same node")
    void testPrefixHashConsistentMapping() {
        UpstreamNode node1 = new UpstreamNode("node-1", "http://node1:8000");
        UpstreamNode node2 = new UpstreamNode("node-2", "http://node2:8000");
        UpstreamNode node3 = new UpstreamNode("node-3", "http://node3:8000");
        List<UpstreamNode> nodes = List.of(node1, node2, node3);

        PrefixHashLoadBalancer lb = new PrefixHashLoadBalancer();

        ChatCompletionRequest reqSystemA1 = new ChatCompletionRequest("model-x", List.of(
                ChatMessage.system("You are an expert Java architect."),
                ChatMessage.user("How do I implement virtual threads?")
        ), false, 0.7);

        ChatCompletionRequest reqSystemA2 = new ChatCompletionRequest("model-x", List.of(
                ChatMessage.system("You are an expert Java architect."),
                ChatMessage.user("What is the cost of context switching?")
        ), false, 0.7);

        UpstreamNode target1 = lb.choose(nodes, reqSystemA1);
        assertNotNull(target1);

        for (int i = 0; i < 20; i++) {
            UpstreamNode target = lb.choose(nodes, reqSystemA2);
            assertEquals(target1.getId(), target.getId(), "Identical system prompt prefix must map to the same node");
        }
    }

    @Test
    @DisplayName("PrefixHashLoadBalancer provides even distribution across distinct prompt prefixes")
    void testPrefixHashEvenDistribution() {
        UpstreamNode node1 = new UpstreamNode("node-1", "http://node1:8000");
        UpstreamNode node2 = new UpstreamNode("node-2", "http://node2:8000");
        UpstreamNode node3 = new UpstreamNode("node-3", "http://node3:8000");
        List<UpstreamNode> nodes = List.of(node1, node2, node3);

        PrefixHashLoadBalancer lb = new PrefixHashLoadBalancer();
        Map<String, Integer> counts = new HashMap<>();
        counts.put("node-1", 0);
        counts.put("node-2", 0);
        counts.put("node-3", 0);

        int sampleCount = 900;
        for (int i = 0; i < sampleCount; i++) {
            ChatCompletionRequest req = new ChatCompletionRequest("qwen", List.of(
                    ChatMessage.user("Unique user prompt context number #" + i)
            ), false, 0.7);
            UpstreamNode chosen = lb.choose(nodes, req);
            assertNotNull(chosen);
            counts.compute(chosen.getId(), (k, v) -> v == null ? 1 : v + 1);
        }

        // Each node should receive at least 20% of traffic (180 out of 900, expected 300 each)
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            assertTrue(entry.getValue() >= 180,
                    "Node " + entry.getKey() + " received " + entry.getValue() + " requests, expected >= 180");
        }
    }

    @Test
    @DisplayName("PrefixHashLoadBalancer fallback to model and random keys")
    void testPrefixHashFallback() {
        UpstreamNode node1 = new UpstreamNode("node-1", "http://node1:8000");
        UpstreamNode node2 = new UpstreamNode("node-2", "http://node2:8000");
        List<UpstreamNode> nodes = List.of(node1, node2);

        PrefixHashLoadBalancer lb = new PrefixHashLoadBalancer();

        // Fallback to model name when messages is empty
        ChatCompletionRequest reqWithModelOnly = new ChatCompletionRequest("deepseek-v3", Collections.emptyList(), false, 0.7);
        UpstreamNode chosenModel1 = lb.choose(nodes, reqWithModelOnly);
        UpstreamNode chosenModel2 = lb.choose(nodes, reqWithModelOnly);
        assertNotNull(chosenModel1);
        assertEquals(chosenModel1.getId(), chosenModel2.getId(), "Identical model fallback must be deterministic");

        // Fallback when request is null
        UpstreamNode chosenRandom = lb.choose(nodes, null);
        assertNotNull(chosenRandom);
    }

    @Test
    @DisplayName("RoundRobinLoadBalancer distributes cyclically")
    void testRoundRobinLoadBalancer() {
        UpstreamNode node1 = new UpstreamNode("node-1", "http://node1:8000");
        UpstreamNode node2 = new UpstreamNode("node-2", "http://node2:8000");
        UpstreamNode node3 = new UpstreamNode("node-3", "http://node3:8000");
        List<UpstreamNode> nodes = List.of(node1, node2, node3);

        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer(0);
        ChatCompletionRequest request = new ChatCompletionRequest("gpt-4o", List.of(ChatMessage.user("hi")), false, 0.7);

        assertEquals("node-1", lb.choose(nodes, request).getId());
        assertEquals("node-2", lb.choose(nodes, request).getId());
        assertEquals("node-3", lb.choose(nodes, request).getId());
        assertEquals("node-1", lb.choose(nodes, request).getId());
        assertEquals("node-2", lb.choose(nodes, request).getId());
        assertEquals("node-3", lb.choose(nodes, request).getId());
    }

    @Test
    @DisplayName("Availability filtering skips unhealthy nodes and nodes with OPEN circuit breakers")
    void testAvailabilityFiltering() {
        UpstreamNode healthyNode = new UpstreamNode("node-healthy", "http://healthy:8000");
        healthyNode.setActiveRequests(20);

        UpstreamNode unhealthyNode = new UpstreamNode("node-unhealthy", "http://unhealthy:8000");
        unhealthyNode.setActiveRequests(0);
        unhealthyNode.setHealthy(false); // isHealthy = false

        UpstreamNode brokenNode = new UpstreamNode("node-broken", "http://broken:8000");
        brokenNode.setActiveRequests(0);
        // Trip circuit breaker by 3 consecutive failures
        brokenNode.recordFailure();
        brokenNode.recordFailure();
        brokenNode.recordFailure();

        assertFalse(unhealthyNode.isAvailable(), "Unhealthy node must not be available");
        assertTrue(brokenNode.getCircuitBreaker().isOpen(), "Broken node circuit breaker must be OPEN");
        assertFalse(brokenNode.isAvailable(), "Node with OPEN circuit breaker must not be available");
        assertTrue(healthyNode.isAvailable(), "Healthy node must be available");

        List<UpstreamNode> candidates = List.of(unhealthyNode, brokenNode, healthyNode);
        ChatCompletionRequest request = new ChatCompletionRequest("test", List.of(ChatMessage.user("test")), false, 0.7);

        List<ClusterLoadBalancer> loadBalancers = List.of(
                new RoundRobinLoadBalancer(),
                new LeastRequestsLoadBalancer(),
                new PrefixHashLoadBalancer()
        );

        for (ClusterLoadBalancer lb : loadBalancers) {
            UpstreamNode chosen = lb.choose(candidates, request);
            assertNotNull(chosen, lb.getClass().getSimpleName() + " should choose an available node");
            assertEquals("node-healthy", chosen.getId(),
                    lb.getClass().getSimpleName() + " must only select the available node");
        }

        // When healthyNode also becomes unavailable
        healthyNode.setHealthy(false);
        for (ClusterLoadBalancer lb : loadBalancers) {
            UpstreamNode chosen = lb.choose(candidates, request);
            assertNull(chosen, lb.getClass().getSimpleName() + " must return null when no node is available");
        }
    }

    @Test
    @DisplayName("Null or empty candidate lists return null gracefully")
    void testNullOrEmptyCandidates() {
        List<ClusterLoadBalancer> loadBalancers = List.of(
                new RoundRobinLoadBalancer(),
                new LeastRequestsLoadBalancer(),
                new PrefixHashLoadBalancer()
        );
        ChatCompletionRequest request = new ChatCompletionRequest("test", List.of(ChatMessage.user("test")), false, 0.7);

        for (ClusterLoadBalancer lb : loadBalancers) {
            assertNull(lb.choose(null, request));
            assertNull(lb.choose(Collections.emptyList(), request));

            List<UpstreamNode> listWithNulls = new ArrayList<>();
            listWithNulls.add(null);
            assertNull(lb.choose(listWithNulls, request));
        }
    }
}
