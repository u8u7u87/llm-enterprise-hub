package com.enterprise.llm.ratelimit;

import com.enterprise.llm.tenant.TenantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRateLimiterTest {

    private TenantRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new TenantRateLimiter();
        rateLimiter.clear();
    }

    @Test
    void shouldAllowTrafficWithinQpsLimit() {
        TenantEntity tenant = new TenantEntity(
                "tenant-rate-1",
                "Rate Test Tenant",
                "sk-rate-key-1",
                5, // 5 QPS
                100000,
                0,
                true
        );

        // 5 immediate calls should all pass
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryAcquire(tenant)).isTrue();
        }

        // 6th immediate call should be rejected (burst exhausted)
        assertThat(rateLimiter.tryAcquire(tenant)).isFalse();
    }

    @Test
    void shouldBlockWhenDailyTokenQuotaExceeded() {
        TenantEntity tenant = new TenantEntity(
                "tenant-quota-exceeded",
                "Quota Exhausted Tenant",
                "sk-rate-key-2",
                100,
                5000, // 5000 token limit
                5000, // already used 5000 tokens
                true
        );

        assertThat(rateLimiter.tryAcquire(tenant)).isFalse();
    }

    @Test
    void shouldBlockWhenTpmLimitExceeded() {
        TenantEntity tenant = new TenantEntity(
                "tenant-tpm-test",
                "TPM Test Tenant",
                "sk-tpm-1",
                100, // QPS
                1000, // RPM
                500L, // 500 TPM
                100000L,
                0L,
                true
        );

        // First call requests 400 tokens -> passes
        RateLimitResult r1 = rateLimiter.tryAcquireDetailed(tenant, 400);
        assertThat(r1.allowed()).isTrue();

        // Second call requests 200 tokens -> 400 + 200 = 600 > 500 TPM -> rejected
        RateLimitResult r2 = rateLimiter.tryAcquireDetailed(tenant, 200);
        assertThat(r2.allowed()).isFalse();
        assertThat(r2.rejectionReason()).contains("TPM_EXCEEDED");

        // Now reconcile usage: actual was only 100 instead of 400
        rateLimiter.recordUsage(tenant, 100L, 400L);

        // Now 200 tokens request should pass (100 + 200 = 300 <= 500)
        RateLimitResult r3 = rateLimiter.tryAcquireDetailed(tenant, 200);
        assertThat(r3.allowed()).isTrue();
    }

    @Test
    void shouldBlockWhenRpmLimitExceeded() {
        TenantEntity tenant = new TenantEntity(
                "tenant-rpm-test",
                "RPM Test Tenant",
                "sk-rpm-1",
                100, // high QPS
                2,   // 2 RPM limit
                100000L, // high TPM
                100000L,
                0L,
                true
        );

        assertThat(rateLimiter.tryAcquire(tenant, 50)).isTrue();
        assertThat(rateLimiter.tryAcquire(tenant, 50)).isTrue();

        RateLimitResult r3 = rateLimiter.tryAcquireDetailed(tenant, 50);
        assertThat(r3.allowed()).isFalse();
        assertThat(r3.rejectionReason()).contains("RPM_EXCEEDED");
    }
}
