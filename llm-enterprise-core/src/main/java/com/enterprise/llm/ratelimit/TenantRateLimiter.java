package com.enterprise.llm.ratelimit;

import com.enterprise.llm.tenant.TenantEntity;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TenantRateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(TenantEntity tenant) {
        if (tenant == null) {
            return false;
        }

        // 1. Check daily token quota
        if (tenant.getDailyTokenQuota() > 0 && tenant.getUsedTokens() >= tenant.getDailyTokenQuota()) {
            return false;
        }

        // 2. Check QPS TokenBucket
        TokenBucket bucket = buckets.computeIfAbsent(
                tenant.getId(),
                id -> new TokenBucket(tenant.getQpsLimit(), tenant.getQpsLimit())
        );

        return bucket.tryConsume();
    }

    public void clear() {
        buckets.clear();
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
