package com.enterprise.llm.resilience;

/**
 * 熔断器状态定义
 */
public enum CircuitBreakerState {
    /**
     * 闭合正常状态：放行所有请求，统计错误率
     */
    CLOSED,

    /**
     * 开启熔断状态：快速失败，所有主模型请求被拦截降级
     */
    OPEN,

    /**
     * 半开探测状态：冷却窗口过后，允许限量探针请求探测上游服务恢复状况
     */
    HALF_OPEN
}
