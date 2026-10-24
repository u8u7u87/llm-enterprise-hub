package com.enterprise.llm.cluster.lb;

import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.model.ChatCompletionRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cluster load balancing interface for selecting an optimal upstream inference node
 * (e.g. vLLM, Triton, Ollama) according to workload requirements.
 */
public interface ClusterLoadBalancer {

    /**
     * Chooses an available UpstreamNode for the given request.
     *
     * @param candidateNodes list of candidate upstream nodes
     * @param request the chat completion request
     * @return chosen UpstreamNode, or null if candidates is null or no node is available
     */
    UpstreamNode choose(List<UpstreamNode> candidateNodes, ChatCompletionRequest request);

    /**
     * Filters candidate nodes to retain only those that are currently available.
     *
     * @param candidateNodes list of candidate nodes
     * @return list of available nodes (never null)
     */
    default List<UpstreamNode> filterAvailable(List<UpstreamNode> candidateNodes) {
        if (candidateNodes == null || candidateNodes.isEmpty()) {
            return Collections.emptyList();
        }
        List<UpstreamNode> available = new ArrayList<>(candidateNodes.size());
        for (UpstreamNode node : candidateNodes) {
            if (node != null && node.isAvailable()) {
                available.add(node);
            }
        }
        return available;
    }
}
