package com.enterprise.llm.cluster.lb;

import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.model.ChatCompletionRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Smooth round-robin load balancer over available upstream nodes using an atomic counter.
 */
public class RoundRobinLoadBalancer implements ClusterLoadBalancer {

    private final AtomicInteger position = new AtomicInteger(0);

    public RoundRobinLoadBalancer() {
    }

    public RoundRobinLoadBalancer(int initialPosition) {
        this.position.set(initialPosition);
    }

    @Override
    public UpstreamNode choose(List<UpstreamNode> candidateNodes, ChatCompletionRequest request) {
        List<UpstreamNode> available = filterAvailable(candidateNodes);
        if (available.isEmpty()) {
            return null;
        }
        int index = Math.floorMod(position.getAndIncrement(), available.size());
        return available.get(index);
    }
}
