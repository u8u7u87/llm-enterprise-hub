package com.enterprise.llm.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlidingWindowRateLimiterTest {

    private SlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new SlidingWindowRateLimiter();
    }

    @Test
    @DisplayName("Should grant permission when within RPM and TPM limits")
    void shouldGrantPermissionWhenWithinLimits() {
        RateLimitResult result = limiter.tryAcquire(10, 1000L, 100);
        assertThat(result.allowed()).isTrue();
        assertThat(result.rejectionReason()).isNull();
    }

    @Test
    @DisplayName("Should reject when RPM limit is exceeded")
    void shouldRejectWhenRpmExceeded() {
        int rpmLimit = 3;
        long tpmLimit = 100_000L;

        assertThat(limiter.tryAcquire(rpmLimit, tpmLimit, 10).allowed()).isTrue();
        assertThat(limiter.tryAcquire(rpmLimit, tpmLimit, 10).allowed()).isTrue();
        assertThat(limiter.tryAcquire(rpmLimit, tpmLimit, 10).allowed()).isTrue();

        RateLimitResult fourth = limiter.tryAcquire(rpmLimit, tpmLimit, 10);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.rejectionReason()).contains("RPM_EXCEEDED");
    }

    @Test
    @DisplayName("Should reject when TPM limit is exceeded by estimated tokens")
    void shouldRejectWhenTpmExceeded() {
        int rpmLimit = 100;
        long tpmLimit = 500L;

        assertThat(limiter.tryAcquire(rpmLimit, tpmLimit, 300).allowed()).isTrue();

        // 300 + 250 = 550 > 500 TPM limit
        RateLimitResult second = limiter.tryAcquire(rpmLimit, tpmLimit, 250);
        assertThat(second.allowed()).isFalse();
        assertThat(second.rejectionReason()).contains("TPM_EXCEEDED");
    }

    @Test
    @DisplayName("Should adjust usage via recordUsage reconciliation")
    void shouldAdjustUsageWithReconciliation() {
        int rpmLimit = 100;
        long tpmLimit = 500L;

        // Estimated 400 tokens reserved
        assertThat(limiter.tryAcquire(rpmLimit, tpmLimit, 400).allowed()).isTrue();

        // But model only used 150 tokens -> reclaim 250 tokens
        limiter.recordUsage(150L, 400L);

        // Now next call requesting 200 tokens should succeed (150 + 200 = 350 <= 500)
        RateLimitResult next = limiter.tryAcquire(rpmLimit, tpmLimit, 200);
        assertThat(next.allowed()).isTrue();
    }
}
