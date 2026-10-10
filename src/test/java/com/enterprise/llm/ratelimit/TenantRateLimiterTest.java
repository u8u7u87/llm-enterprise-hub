package com.enterprise.llm.ratelimit;

import com.enterprise.llm.auth.TenantSecurityContext;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TenantRateLimiterTest {

    @Autowired
    private TenantRateLimiter rateLimiter;

    @Autowired
    private TenantService tenantService;

    @BeforeEach
    void setUp() {
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
}
