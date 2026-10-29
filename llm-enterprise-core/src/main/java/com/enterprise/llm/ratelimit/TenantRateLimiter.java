package com.enterprise.llm.ratelimit;

import com.enterprise.llm.tenant.TenantEntity;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class TenantRateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SlidingWindowRateLimiter> slidingLimiters = new ConcurrentHashMap<>();

    public RateLimitResult tryAcquireDetailed(TenantEntity tenant, int estimatedTokens) {
        if (tenant == null) {
            return RateLimitResult.rejected("TENANT_NULL", 0);
        }

        // 1. Check daily token quota
        if (tenant.getDailyTokenQuota() > 0 && tenant.getUsedTokens() >= tenant.getDailyTokenQuota()) {
            return RateLimitResult.rejected("DAILY_TOKEN_QUOTA_EXCEEDED", 60000);
        }

        // 2. Check QPS TokenBucket
        if (tenant.getQpsLimit() > 0) {
            TokenBucket bucket = buckets.computeIfAbsent(
                    tenant.getId(),
                    id -> new TokenBucket(tenant.getQpsLimit(), tenant.getQpsLimit())
            );
            if (!bucket.tryConsume()) {
                return RateLimitResult.rejected("QPS_EXCEEDED", 500);
            }
        }

        // 3. Check Sliding Window (RPM + TPM)
        if (tenant.getRpmLimit() > 0 || tenant.getTpmLimit() > 0) {
            SlidingWindowRateLimiter slidingLimiter = slidingLimiters.computeIfAbsent(
                    tenant.getId(),
                    id -> new SlidingWindowRateLimiter()
            );
            RateLimitResult result = slidingLimiter.tryAcquire(tenant.getRpmLimit(), tenant.getTpmLimit(), estimatedTokens);
            if (!result.allowed()) {
                return result;
            }
        }

        return RateLimitResult.granted();
    }

    public RateLimitResult tryAcquireTpm(TenantEntity tenant, int estimatedTokens) {
        if (tenant == null) {
            return RateLimitResult.rejected("TENANT_NULL", 0);
        }
        if (tenant.getTpmLimit() > 0) {
            SlidingWindowRateLimiter slidingLimiter = slidingLimiters.computeIfAbsent(
                    tenant.getId(),
                    id -> new SlidingWindowRateLimiter()
            );
            return slidingLimiter.tryAcquire(0, tenant.getTpmLimit(), estimatedTokens);
        }
        return RateLimitResult.granted();
    }

    public boolean tryAcquire(TenantEntity tenant, int estimatedTokens) {
        return tryAcquireDetailed(tenant, estimatedTokens).allowed();
    }

    public boolean tryAcquire(TenantEntity tenant) {
        return tryAcquire(tenant, 0);
    }

    public void recordUsage(TenantEntity tenant, long actualTokens, long estimatedTokens) {
        if (tenant == null) {
            return;
        }
        tenant.setUsedTokens(tenant.getUsedTokens() + actualTokens);
        SlidingWindowRateLimiter slidingLimiter = slidingLimiters.get(tenant.getId());
        if (slidingLimiter != null) {
            slidingLimiter.recordUsage(actualTokens, estimatedTokens);
        }
    }

    public void clear() {
        buckets.clear();
        slidingLimiters.clear();
    }

    private static class TokenBucket {
        private final long capacity;
        private final double refillRatePerMs;
        private double availableTokens;
        private long lastRefillTimestamp;

        public TokenBucket(long capacity, long refillRatePerSec) {
            this.capacity = capacity;
            this.refillRatePerMs = refillRatePerSec / 1000.0;
            this.availableTokens = capacity;
            this.lastRefillTimestamp = System.currentTimeMillis();
        }

        public synchronized boolean tryConsume() {
            refill();
            if (availableTokens >= 1.0) {
                availableTokens -= 1.0;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTimestamp;
            if (elapsed > 0) {
                double tokensToAdd = elapsed * refillRatePerMs;
                availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
                lastRefillTimestamp = now;
            }
        }
    }
}
