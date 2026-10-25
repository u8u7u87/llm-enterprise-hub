package com.enterprise.llm.backend;

import com.enterprise.llm.backend.adapter.*;
import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;
import com.enterprise.llm.cluster.lb.PrefixHashLoadBalancer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineAdaptersTest {

    @Test
    void shouldExtractVllmMetricsAndDetectOverload() {
        VllmInferenceAdapter adapter = new VllmInferenceAdapter();
        assertEquals("vllm", adapter.getEngineType());
        assertInstanceOf(PrefixHashLoadBalancer.class, adapter.createDefaultLoadBalancer());

        UpstreamNode node = new UpstreamNode("vllm-node-1", "http://10.0.1.1:8000/v1");

        String vllmMetrics = """
                # HELP vllm:num_requests_waiting Number of requests waiting to be processed.
                # TYPE vllm:num_requests_waiting gauge
                vllm:num_requests_waiting{model_name="qwen2.5:72b"} 18.0
                # HELP vllm:gpu_cache_usage_factor GPU KV-cache usage factor.
                # TYPE vllm:gpu_cache_usage_factor gauge
                vllm:gpu_cache_usage_factor{model_name="qwen2.5:72b"} 0.92
                """;

        adapter.extractMetrics(node, vllmMetrics);

        assertEquals(18, node.getWaitingRequests());
        assertEquals(0.92, node.getGpuCacheUsage(), 0.001);

        assertFalse(node.isOverloaded(20, 0.95));
        assertTrue(node.isOverloaded(15, 0.95)); // exceeded waiting requests threshold
    }

    @Test
    void shouldExtractSglangMetricsAndDetectOverload() {
        SglangInferenceAdapter adapter = new SglangInferenceAdapter();
        assertEquals("sglang", adapter.getEngineType());
        assertInstanceOf(PrefixHashLoadBalancer.class, adapter.createDefaultLoadBalancer());

        UpstreamNode node = new UpstreamNode("sglang-node-1", "http://10.0.2.1:8000/v1");

        String sglangMetrics = """
                # HELP sglang:num_waiting_reqs The number of waiting requests.
                sglang:num_waiting_reqs 7.0
                # HELP sglang:gpu_cache_usage The GPU cache usage.
                sglang:gpu_cache_usage 0.96
                """;

        adapter.extractMetrics(node, sglangMetrics);

        assertEquals(7, node.getWaitingRequests());
        assertEquals(0.96, node.getGpuCacheUsage(), 0.001);

        assertTrue(node.isOverloaded(20, 0.95)); // exceeded 95% GPU cache usage threshold
    }

    @Test
    void shouldVerifyTritonAndRayAndDirectAdapters() {
        TritonInferenceAdapter triton = new TritonInferenceAdapter();
        assertEquals("triton", triton.getEngineType());
        assertInstanceOf(LeastRequestsLoadBalancer.class, triton.createDefaultLoadBalancer());

        RayServeInferenceAdapter ray = new RayServeInferenceAdapter();
        assertEquals("ray", ray.getEngineType());
        assertInstanceOf(LeastRequestsLoadBalancer.class, ray.createDefaultLoadBalancer());

        DirectInferenceAdapter direct = new DirectInferenceAdapter();
        assertEquals("direct", direct.getEngineType());
        assertInstanceOf(LeastRequestsLoadBalancer.class, direct.createDefaultLoadBalancer());
    }

    @Test
    void shouldHandleNodeOverloadBoundaryLogic() {
        UpstreamNode node = new UpstreamNode("test-node", "http://localhost:8000/v1");

        node.setWaitingRequests(5);
        node.setGpuCacheUsage(0.85);
        assertFalse(node.isOverloaded(10, 0.90));

        node.setWaitingRequests(10);
        assertTrue(node.isOverloaded(10, 0.90));

        node.setWaitingRequests(3);
        node.setGpuCacheUsage(0.91);
        assertTrue(node.isOverloaded(10, 0.90));

        // When thresholds are 0, overload is disabled
        assertFalse(node.isOverloaded(0, 0.0));
    }
}
