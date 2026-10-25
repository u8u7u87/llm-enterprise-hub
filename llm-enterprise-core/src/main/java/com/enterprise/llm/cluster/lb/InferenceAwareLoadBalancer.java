package com.enterprise.llm.cluster.lb;

import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Composite inference-aware load balancer for high-performance clusters (vLLM, SGLang, Triton).
 * Combines:
 * 1. Overload protection (penalizes nodes with high waitingRequests or high gpuCacheUsage);
 * 2. Prefix Cache affinity (hashes System Prompt / prefix text to stick to the same node for APC/Radix KV cache hit);
 * 3. Dynamic cost scoring (Cost = activeRequests * 1.0 + waitingRequests * 2.0).
 */
public class InferenceAwareLoadBalancer implements ClusterLoadBalancer {

    private final boolean prefixAffinityEnabled;
    private final int maxWaitingThreshold;
    private final double maxGpuCacheThreshold;

    public InferenceAwareLoadBalancer(boolean prefixAffinityEnabled, int maxWaitingThreshold, double maxGpuCacheThreshold) {
        this.prefixAffinityEnabled = prefixAffinityEnabled;
        this.maxWaitingThreshold = Math.max(0, maxWaitingThreshold);
        this.maxGpuCacheThreshold = Math.max(0.0, maxGpuCacheThreshold);
    }

    public InferenceAwareLoadBalancer() {
        this(true, 20, 0.95);
    }

    @Override
    public UpstreamNode choose(List<UpstreamNode> candidateNodes, ChatCompletionRequest request) {
        List<UpstreamNode> available = filterAvailable(candidateNodes);
        if (available.isEmpty()) {
            return null;
        }

        // 1. Separate non-overloaded nodes from overloaded nodes
        List<UpstreamNode> healthyPool = new ArrayList<>();
        for (UpstreamNode node : available) {
            if (!node.isOverloaded(maxWaitingThreshold, maxGpuCacheThreshold)) {
                healthyPool.add(node);
            }
        }

        // If all nodes are currently overloaded, degrade gracefully to the full available pool
        List<UpstreamNode> effectivePool = healthyPool.isEmpty() ? available : healthyPool;

        if (effectivePool.size() == 1) {
            return effectivePool.get(0);
        }

        // 2. If prefix affinity is enabled and request has prompt prefix, consider affinity
        if (prefixAffinityEnabled && request != null) {
            String prefix = extractPrefixKey(request);
            if (prefix != null && !prefix.isBlank()) {
                int hash = computeHash(prefix);
                int primaryIndex = Math.abs(hash) % effectivePool.size();
                UpstreamNode affinityNode = effectivePool.get(primaryIndex);

                // If affinity node is not heavily loaded (e.g. activeRequests <= 3 and waiting <= 2), choose it!
                if (affinityNode.getActiveRequests() <= 3 && affinityNode.getWaitingRequests() <= 2) {
                    return affinityNode;
                }
            }
        }

        // 3. Fallback to lowest weighted load cost: Cost = activeRequests * 1.0 + waitingRequests * 2.0
        return effectivePool.stream()
                .min(Comparator.comparingDouble(this::computeLoadCost))
                .orElse(effectivePool.get(0));
    }

    private double computeLoadCost(UpstreamNode node) {
        return (node.getActiveRequests() * 1.0) + (node.getWaitingRequests() * 2.0) + (node.getGpuCacheUsage() * 5.0);
    }

    private String extractPrefixKey(ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request.model();
        }
        for (ChatMessage msg : request.messages()) {
            if ("system".equalsIgnoreCase(msg.role()) && msg.content() != null && !msg.content().isBlank()) {
                return msg.content();
            }
        }
        ChatMessage first = request.messages().get(0);
        return first.content() != null ? first.content() : request.model();
    }

    private int computeHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return ((digest[0] & 0xFF) << 24)
                    | ((digest[1] & 0xFF) << 16)
                    | ((digest[2] & 0xFF) << 8)
                    | (digest[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            return input.hashCode();
        }
    }
}
