package com.enterprise.llm.backend.adapter;

import com.enterprise.llm.backend.EngineType;
import com.enterprise.llm.cluster.lb.ClusterLoadBalancer;
import com.enterprise.llm.cluster.lb.LeastRequestsLoadBalancer;

/**
 * 无框架直连推理适配器。
 * 适用于 Ollama、本地 llama.cpp 以及 OpenAI 兼容的商用云 API。
 */
public class DirectInferenceAdapter extends AbstractInferenceAdapter {

    @Override
    public String getEngineType() {
        return EngineType.DIRECT.getCode();
    }

    @Override
    public ClusterLoadBalancer createDefaultLoadBalancer() {
        return new LeastRequestsLoadBalancer();
    }

    @Override
    protected String primaryProbePath() {
        // 直连形态没有专用健康端点，直接用 OpenAI 兼容的模型列表接口探活
        return "/v1/models";
    }

    @Override
    protected String fallbackProbePath() {
        // 主探针已是模型列表接口，无需兜底
        return null;
    }
}
