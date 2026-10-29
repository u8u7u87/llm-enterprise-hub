package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.backend.EngineType;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;

/**
 * Ray Serve 分布式推理适配器。
 * 探针优先走 Ray Serve HTTP Ingress 的 {@code /-/healthz}，失败后回退到 {@code /v1/models}。
 */
public class RayServeInferenceAdapter extends AbstractInferenceAdapter {

    @Override
    public String getEngineType() {
        return EngineType.RAY.getCode();
    }

    @Override
    public ClusterLoadBalancer createDefaultLoadBalancer() {
        return new LeastRequestsLoadBalancer();
    }

    @Override
    protected String primaryProbePath() {
        return "/-/healthz";
    }
}
