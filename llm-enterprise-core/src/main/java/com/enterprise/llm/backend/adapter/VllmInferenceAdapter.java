package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.backend.EngineType;
import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.PrefixHashLoadBalancer;

import java.util.regex.Pattern;

/**
 * vLLM 专用推理适配器。
 * 针对 PagedAttention、Automatic Prefix Caching（APC）与队列背压监控做了适配。
 */
public class VllmInferenceAdapter extends AbstractInferenceAdapter {

    private static final Pattern WAITING_REQUESTS_PATTERN = Pattern.compile(
            "(?:vllm:)?num_requests_waiting(?:\\{[^}]*\\})?\\s+([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern GPU_CACHE_PATTERN = Pattern.compile(
            "(?:vllm:)?gpu_cache_usage_factor(?:\\{[^}]*\\})?\\s+([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    @Override
    public String getEngineType() {
        return EngineType.VLLM.getCode();
    }

    @Override
    public ClusterLoadBalancer createDefaultLoadBalancer() {
        // vLLM 的 APC 命中率高度依赖前缀稳定，因此默认用前缀哈希路由
        return new PrefixHashLoadBalancer();
    }

    @Override
    protected String primaryProbePath() {
        return "/health";
    }

    @Override
    protected void doExtractMetrics(UpstreamNode node, String prometheusText) {
        applyWaitingRequests(node, WAITING_REQUESTS_PATTERN, prometheusText);
        applyGpuCacheUsage(node, GPU_CACHE_PATTERN, prometheusText);
    }
}
