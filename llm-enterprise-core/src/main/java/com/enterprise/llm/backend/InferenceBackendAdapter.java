package com.enterprise.llm.backend;

import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.model.ChatCompletionRequest;

import java.net.http.HttpClient;

/**
 * Pluggable SPI interface for inference backend engine adapters.
 * Each adapter encapsulates engine-specific health probes, default load balancing strategies,
 * Prometheus metrics extraction (e.g. queue backpressure), and request transformations.
 */
public interface InferenceBackendAdapter {

    /**
     * Unique engine type identifier (e.g., "direct", "vllm", "sglang", "triton", "ray").
     */
    String getEngineType();

    /**
     * Recommended default load balancer for this engine type
     * (e.g., PrefixHashLoadBalancer for vLLM/SGLang cache affinity, LeastRequestsLoadBalancer for Direct/Triton).
     */
    ClusterLoadBalancer createDefaultLoadBalancer();

    /**
     * Performs an engine-specific health check on the upstream node.
     *
     * @param node the target upstream node
     * @param httpClient HTTP client for executing the probe
     * @return true if node is healthy, false otherwise
     */
    boolean checkHealth(UpstreamNode node, HttpClient httpClient);

    /**
     * Parses the Prometheus /metrics output from the inference engine and updates
     * node-level backpressure metrics (e.g. waitingRequests, gpuCacheUsage).
     *
     * @param node the target upstream node
     * @param prometheusText raw text from the /metrics endpoint
     */
    default void extractMetrics(UpstreamNode node, String prometheusText) {
        // Default no-op
    }

    /**
     * Engine-specific request adaptation, header injection, or parameter normalization.
     *
     * @param request original chat completion request
     * @param node target node chosen for the request
     * @return adapted chat completion request
     */
    default ChatCompletionRequest adaptRequest(ChatCompletionRequest request, UpstreamNode node) {
        return request;
    }
}
