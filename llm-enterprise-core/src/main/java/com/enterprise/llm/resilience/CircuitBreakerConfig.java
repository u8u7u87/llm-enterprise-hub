package com.enterprise.llm.resilience;

/**
 * 熔断器配置参数
 *
 * @param failureThreshold          连续失败达到多少次后进入 OPEN 状态（默认 3）
 * @param cooldownDurationMillis    进入 OPEN 状态后的冷却时间（毫秒），超时后转为 HALF_OPEN（默认 15000ms）
 * @param halfOpenSuccessThreshold  HALF_OPEN 状态下连续成功多少次后自愈回到 CLOSED（默认 1）
 * @param halfOpenMaxPermits        HALF_OPEN 状态下允许并发执行的最大探针请求数（默认 1）
 */
public record CircuitBreakerConfig(
        int failureThreshold,
        long cooldownDurationMillis,
        int halfOpenSuccessThreshold,
        int halfOpenMaxPermits
) {
    public static CircuitBreakerConfig defaultConfig() {
        return new CircuitBreakerConfig(3, 15000L, 1, 1);
    }
}
