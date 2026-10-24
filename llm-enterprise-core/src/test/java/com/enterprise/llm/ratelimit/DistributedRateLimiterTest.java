package com.enterprise.llm.ratelimit;

import com.enterprise.llm.tenant.TenantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributedRateLimiterTest {

    @Mock
    private TenantRateLimiter localFallbackLimiter;

    @Mock
    private DistributedRateLimiter.DistributedBackend backend;

    private DistributedRateLimiter limiter;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenant = new TenantEntity("tenant-prod-1", "TestCorp", "key-123", 10, 100000, 0, true);
    }

    @Test
    void shouldDelegateToDistributedBackendWhenHealthy() throws Exception {
        when(backend.tryAcquire(tenant, 100)).thenReturn(RateLimitResult.granted());

        limiter = new DistributedRateLimiter(backend, localFallbackLimiter, 3);
        RateLimitResult result = limiter.tryAcquire(tenant, 100);

        assertThat(result.allowed()).isTrue();
        verify(backend).tryAcquire(tenant, 100);
        verifyNoInteractions(localFallbackLimiter);
    }

    @Test
    void shouldTripCircuitAndFallbackToLocalWhenBackendFailsConsistently() throws Exception {
        when(backend.tryAcquire(any(), anyInt())).thenThrow(new IOException("Redis connection refused"));
        when(localFallbackLimiter.tryAcquireDetailed(any(), anyInt())).thenReturn(RateLimitResult.granted());

        limiter = new DistributedRateLimiter(backend, localFallbackLimiter, 2);

        // 1st failure: degrades to fallback, circuit still closed
        RateLimitResult r1 = limiter.tryAcquire(tenant, 100);
        assertThat(r1.allowed()).isTrue();
        assertThat(limiter.isCircuitOpen()).isFalse();

        // 2nd failure: threshold reached (2), circuit trips OPEN
        RateLimitResult r2 = limiter.tryAcquire(tenant, 100);
        assertThat(r2.allowed()).isTrue();
        assertThat(limiter.isCircuitOpen()).isTrue();

        // 3rd attempt: circuit is open, directly calls local fallback without touching broken backend
        clearInvocations(backend);
        RateLimitResult r3 = limiter.tryAcquire(tenant, 100);
        assertThat(r3.allowed()).isTrue();
        verifyNoInteractions(backend);

        // Reset circuit
        limiter.resetCircuit();
        assertThat(limiter.isCircuitOpen()).isFalse();
    }

    @Test
    void shouldPropagateRejectionFromLocalFallbackWhenDegraded() throws Exception {
        when(backend.tryAcquire(any(), anyInt())).thenThrow(new IOException("Timeout"));
        when(localFallbackLimiter.tryAcquireDetailed(any(), anyInt()))
                .thenReturn(RateLimitResult.rejected("QPS_EXCEEDED", 500));

        limiter = new DistributedRateLimiter(backend, localFallbackLimiter, 1);
        RateLimitResult result = limiter.tryAcquire(tenant, 100);

        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("QPS_EXCEEDED");
    }
}
