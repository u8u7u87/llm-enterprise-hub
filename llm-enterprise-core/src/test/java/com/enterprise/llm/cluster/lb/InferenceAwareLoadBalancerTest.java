package com.enterprise.llm.cluster.lb;

import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InferenceAwareLoadBalancerTest {

    @Test
    void shouldFilterOutOverloadedNodesWhenHealthyCandidatesExist() {
        InferenceAwareLoadBalancer lb = new InferenceAwareLoadBalancer(false, 10, 0.90);

        UpstreamNode normalNode = new UpstreamNode("node-healthy", "http://10.0.1.1:8000/v1");
        normalNode.setWaitingRequests(2);
        normalNode.setGpuCacheUsage(0.50);

        UpstreamNode overloadedNode = new UpstreamNode("node-overloaded", "http://10.0.1.2:8000/v1");
        overloadedNode.setWaitingRequests(15); // Exceeded 10
        overloadedNode.setGpuCacheUsage(0.98);

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:72b",
                List.of(new ChatMessage("user", "hello world")),
                false, 0.7
        );

        UpstreamNode chosen = lb.choose(List.of(normalNode, overloadedNode), request);
        assertNotNull(chosen);
        assertEquals("node-healthy", chosen.getId());
    }

    @Test
    void shouldDegradeToAllAvailableNodesWhenAllAreOverloaded() {
        InferenceAwareLoadBalancer lb = new InferenceAwareLoadBalancer(false, 5, 0.80);

        UpstreamNode node1 = new UpstreamNode("node-1", "http://10.0.1.1:8000/v1");
        node1.setWaitingRequests(8);
        node1.setActiveRequests(4);

        UpstreamNode node2 = new UpstreamNode("node-2", "http://10.0.1.2:8000/v1");
        node2.setWaitingRequests(10);
        node2.setActiveRequests(10);

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:72b",
                List.of(new ChatMessage("user", "test")),
                false, 0.7
        );

        UpstreamNode chosen = lb.choose(List.of(node1, node2), request);
        assertNotNull(chosen);
        // node1 has lower load cost than node2
        assertEquals("node-1", chosen.getId());
    }

    @Test
    void shouldRouteConsistentlyBasedOnSystemPromptPrefixWhenNotHeavilyLoaded() {
        InferenceAwareLoadBalancer lb = new InferenceAwareLoadBalancer(true, 20, 0.95);

        UpstreamNode nodeA = new UpstreamNode("node-A", "http://10.0.1.1:8000/v1");
        UpstreamNode nodeB = new UpstreamNode("node-B", "http://10.0.1.2:8000/v1");

        ChatCompletionRequest request1 = new ChatCompletionRequest(
                "qwen",
                List.of(
                        new ChatMessage("system", "You are an enterprise financial analyst assistant with strict rules."),
                        new ChatMessage("user", "Analyze Q3 revenue report.")
                ),
                false, 0.7
        );

        ChatCompletionRequest request2 = new ChatCompletionRequest(
                "qwen",
                List.of(
                        new ChatMessage("system", "You are an enterprise financial analyst assistant with strict rules."),
                        new ChatMessage("user", "What were the EBITDA margins in Q3?")
                ),
                false, 0.7
        );

        UpstreamNode chosen1 = lb.choose(List.of(nodeA, nodeB), request1);
        UpstreamNode chosen2 = lb.choose(List.of(nodeA, nodeB), request2);

        assertNotNull(chosen1);
        assertNotNull(chosen2);
        // Both requests share the exact same System Prompt prefix, should hit the exact same node
        assertEquals(chosen1.getId(), chosen2.getId());
    }
}
