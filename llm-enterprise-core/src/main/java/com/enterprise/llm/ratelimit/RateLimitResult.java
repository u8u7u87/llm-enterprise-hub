package com.enterprise.llm.ratelimit;

/**
 * 速率限制判定结果
 *
 * @param allowed         是否允许放行
 * @param rejectionReason 拒绝原因代码（如 RPM_EXCEEDED, TPM_EXCEEDED, DAILY_QUOTA_EXCEEDED, QPS_EXCEEDED）
 * @param retryAfterMs    建议退避重试时间（毫秒）
 */
public record RateLimitResult(
        boolean allowed,
        String rejectionReason,
        long retryAfterMs
) {
    public static RateLimitResult granted() {
        return new RateLimitResult(true, null, 0);
    }

    public static RateLimitResult rejected(String rejectionReason, long retryAfterMs) {
        return new RateLimitResult(false, rejectionReason, retryAfterMs);
    }
}
