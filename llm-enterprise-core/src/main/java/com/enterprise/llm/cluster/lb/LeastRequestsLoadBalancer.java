package com.enterprise.llm.cluster.lb;

import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.model.ChatCompletionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Least-requests load balancer for mitigating in-flight queue congestion on inference workers (e.g. vLLM, Triton).
 * Selects the available node with the fewest active requests.
 * In case of ties, round-robin is used among the tied nodes.
 */
public class LeastRequestsLoadBalancer implements ClusterLoadBalancer {

    private final AtomicInteger tieBreaker = new AtomicInteger(0);

    @Override
    public UpstreamNode choose(List<UpstreamNode> candidateNodes, ChatCompletionRequest request) {
        List<UpstreamNode> available = filterAvailable(candidateNodes);
        if (available.isEmpty()) {
            return null;
        }

        int minRequests = Integer.MAX_VALUE;
        for (UpstreamNode node : available) {
            int active = node.getActiveRequests();
            if (active < minRequests) {
                minRequests = active;
            }
        }

        List<UpstreamNode> tiedNodes = new ArrayList<>();
        for (UpstreamNode node : available) {
            if (node.getActiveRequests() == minRequests) {
                tiedNodes.add(node);
            }
        }

        if (tiedNodes.size() == 1) {
            return tiedNodes.get(0);
        }

        int index = Math.floorMod(tieBreaker.getAndIncrement(), tiedNodes.size());
        return tiedNodes.get(index);
    }
}
