package com.enterprise.llm.backend;

import com.enterprise.llm.cluster.RemoteClusterManager;
import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;
import com.enterprise.llm.cluster.lb.PrefixHashLoadBalancer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class RemoteClusterManagerBackendIntegrationTest {

    @Test
    void shouldAdoptEngineRecommendedLoadBalancerWhenNotSpecified() {
        InferenceBackendRegistry vllmRegistry = new InferenceBackendRegistry("vllm");
        RemoteClusterManager vllmMgr = new RemoteClusterManager(new ArrayList<>(), null, null, vllmRegistry);
        assertInstanceOf(PrefixHashLoadBalancer.class, vllmMgr.getLoadBalancer());

        InferenceBackendRegistry directRegistry = new InferenceBackendRegistry("direct");
        RemoteClusterManager directMgr = new RemoteClusterManager(new ArrayList<>(), null, null, directRegistry);
        assertInstanceOf(LeastRequestsLoadBalancer.class, directMgr.getLoadBalancer());
    }

    @Test
    void shouldRegisterNodeWithEngineTypeAndInspectRegistry() {
        InferenceBackendRegistry registry = new InferenceBackendRegistry("direct");
        RemoteClusterManager manager = new RemoteClusterManager(new ArrayList<>(), null, null, registry);

        UpstreamNode vllmNode = new UpstreamNode("node-vllm-1", "http://10.0.1.1:8000/v1");
        vllmNode.setEngineType("vllm");
        assertEquals("vllm", vllmNode.getEngineType());

        manager.registerNode(vllmNode);
        assertEquals(1, manager.getActiveNodeCount());

        InferenceBackendAdapter adapter = manager.getBackendRegistry().getAdapter(vllmNode.getEngineType());
        assertNotNull(adapter);
        assertEquals("vllm", adapter.getEngineType());
    }
}
