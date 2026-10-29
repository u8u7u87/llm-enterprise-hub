package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.backend.EngineType;
import com.enterprise.llm.cluster.UpstreamNode;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.PrefixHashLoadBalancer;

import java.util.regex.Pattern;

/**
 * SGLang 专用推理适配器。
 * 针对 RadixAttention 树形 KV 缓存复用与结构化输出解码做了适配。
 */
public class SglangInferenceAdapter extends AbstractInferenceAdapter {

    private static final Pattern WAITING_REQUESTS_PATTERN = Pattern.compile(
            "(?:sglang[:_])?num_waiting_reqs(?:\\{[^}]*\\})?\\s+([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CACHE_USAGE_PATTERN = Pattern.compile(
            "(?:sglang[:_])?(?:gpu_cache_usage|cache_hit_rate)(?:\\{[^}]*\\})?\\s+([0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String getEngineType() {
        return EngineType.SGLANG.getCode();
    }

    @Override
    public ClusterLoadBalancer createDefaultLoadBalancer() {
        // RadixAttention 树形缓存对前缀稳定高度敏感，默认用前缀哈希路由
        return new PrefixHashLoadBalancer();
    }

    @Override
    protected String primaryProbePath() {
        return "/health";
    }

    @Override
    protected void doExtractMetrics(UpstreamNode node, String prometheusText) {
        applyWaitingRequests(node, WAITING_REQUESTS_PATTERN, prometheusText);
        applyGpuCacheUsage(node, CACHE_USAGE_PATTERN, prometheusText);
    }
}
