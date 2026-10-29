package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.backend.EngineType;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;

/**
 * NVIDIA Triton 推理服务器适配器。
 * 探针优先走 Triton 的就绪端点 {@code /v2/health/ready}，失败后回退到 {@code /v1/models}。
 */
public class TritonInferenceAdapter extends AbstractInferenceAdapter {

    @Override
    public String getEngineType() {
        return EngineType.TRITON.getCode();
    }

    @Override
    public ClusterLoadBalancer createDefaultLoadBalancer() {
        return new LeastRequestsLoadBalancer();
    }

    @Override
    protected String primaryProbePath() {
        return "/v2/health/ready";
    }
}
