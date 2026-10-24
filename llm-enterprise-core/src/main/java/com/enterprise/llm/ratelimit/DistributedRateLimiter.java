package com.enterprise.llm.ratelimit;

import com.enterprise.llm.tenant.TenantEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enterprise Distributed Rate Limiter with Fail-Open Bounded Local Fallback.
 * <p>
 * Ensures consistent rate limiting across multiple gateway pods in production.
 * If the distributed rate limiter backend becomes unreachable or times out,
 * it automatically trips a circuit breaker and degrades to a bounded local limiter,
 * preventing cascade failures and cluster paralysis while maintaining protection.
 */
@Component
public class DistributedRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimiter.class);

    @FunctionalInterface
    public interface DistributedBackend {
        RateLimitResult tryAcquire(TenantEntity tenant, int estimatedTokens) throws Exception;
    }

    private final DistributedBackend distributedBackend;
    private final TenantRateLimiter localFallbackLimiter;
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final int failureThreshold;

    public DistributedRateLimiter() {
        this(new TenantRateLimiter());
    }

    public DistributedRateLimiter(TenantRateLimiter localFallbackLimiter) {
        this(null, localFallbackLimiter, 3);
    }

    public DistributedRateLimiter(DistributedBackend distributedBackend,
                                  TenantRateLimiter localFallbackLimiter,
                                  int failureThreshold) {
        this.distributedBackend = distributedBackend;
        this.localFallbackLimiter = localFallbackLimiter != null ? localFallbackLimiter : new TenantRateLimiter();
        this.failureThreshold = Math.max(1, failureThreshold);
    }

    /**
     * Tries to acquire rate limit permit via distributed backend, falling back to local bounded limiter on failure.
     */
    public RateLimitResult tryAcquire(TenantEntity tenant, int estimatedTokens) {
        if (tenant == null) {
            return RateLimitResult.rejected("TENANT_NULL", 0);
        }

        // If circuit is open or distributed backend is not configured, use local fallback
        if (distributedBackend == null || circuitOpen.get()) {
            return acquireLocalFallback(tenant, estimatedTokens, "CIRCUIT_OPEN_FALLBACK");
        }

        try {
            RateLimitResult result = distributedBackend.tryAcquire(tenant, estimatedTokens);
            // Successful call: reset consecutive failures
            if (consecutiveFailures.get() > 0) {
                consecutiveFailures.set(0);
            }
            return result;
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("Distributed rate limiter backend call failed ({}/{}): {}",
                    failures, failureThreshold, e.getMessage());

            if (failures >= failureThreshold) {
                if (circuitOpen.compareAndSet(false, true)) {
                    log.error("Distributed rate limiter circuit tripped OPEN! Degrading to bounded local fallback.");
                }
            }
            return acquireLocalFallback(tenant, estimatedTokens, "DEGRADED_FALLBACK");
        }
    }

    private RateLimitResult acquireLocalFallback(TenantEntity tenant, int estimatedTokens, String reasonPrefix) {
        RateLimitResult localResult = localFallbackLimiter.tryAcquireDetailed(tenant, estimatedTokens);
        if (!localResult.allowed()) {
            return RateLimitResult.rejected(reasonPrefix + "_" + localResult.rejectionReason(), localResult.retryAfterMs());
        }
        return localResult;
    }

    /**
     * Manually resets the circuit breaker back to closed (healthy).
     */
    public void resetCircuit() {
        consecutiveFailures.set(0);
        circuitOpen.set(false);
        log.info("Distributed rate limiter circuit manually reset to CLOSED.");
    }

    public boolean isCircuitOpen() {
        return circuitOpen.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
