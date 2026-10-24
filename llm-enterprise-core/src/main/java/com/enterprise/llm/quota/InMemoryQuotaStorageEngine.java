package com.enterprise.llm.quota;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free, high-performance in-memory implementation of QuotaStorageEngine.
 * Uses atomic CAS operations per tenant balance to eliminate lock contention.
 */
public class InMemoryQuotaStorageEngine implements QuotaStorageEngine {

    private final ConcurrentHashMap<String, TenantBalance> balances = new ConcurrentHashMap<>();

    private static class TenantBalance {
        final AtomicLong used = new AtomicLong(0);
        final AtomicLong frozen = new AtomicLong(0);

        void reset() {
            used.set(0);
            frozen.set(0);
        }
    }

    private TenantBalance getBalance(String tenantId) {
        return balances.computeIfAbsent(tenantId, k -> new TenantBalance());
    }

    @Override
    public boolean tryReserve(String tenantId, int estimatedTokens, long dailyQuota) {
        if (tenantId == null) {
            return false;
        }
        TenantBalance balance = getBalance(tenantId);
        if (dailyQuota <= 0) {
            balance.frozen.addAndGet(estimatedTokens);
            return true; // Unlimited quota, but still track frozen
        }
        while (true) {
            long currentUsed = balance.used.get();
            long currentFrozen = balance.frozen.get();
            long totalCommitted = currentUsed + currentFrozen + estimatedTokens;

            if (totalCommitted > dailyQuota) {
                return false; // Exceeded
            }

            if (balance.frozen.compareAndSet(currentFrozen, currentFrozen + estimatedTokens)) {
                return true;
            }
        }
    }

    @Override
    public void settle(String tenantId, int estimatedTokens, int actualTokens) {
        if (tenantId == null) {
            return;
        }
        TenantBalance balance = getBalance(tenantId);
        balance.frozen.updateAndGet(f -> Math.max(0, f - estimatedTokens));
        if (actualTokens > 0) {
            balance.used.addAndGet(actualTokens);
        }
    }

    @Override
    public void release(String tenantId, int estimatedTokens) {
        if (tenantId == null) {
            return;
        }
        TenantBalance balance = getBalance(tenantId);
        balance.frozen.updateAndGet(f -> Math.max(0, f - estimatedTokens));
    }

    @Override
    public long getUsedTokens(String tenantId) {
        TenantBalance balance = balances.get(tenantId);
        return balance != null ? balance.used.get() : 0L;
    }

    @Override
    public long getFrozenTokens(String tenantId) {
        TenantBalance balance = balances.get(tenantId);
        return balance != null ? balance.frozen.get() : 0L;
    }

    @Override
    public void reset(String tenantId) {
        TenantBalance balance = balances.get(tenantId);
        if (balance != null) {
            balance.reset();
        }
    }

    public void setUsedTokens(String tenantId, long usedTokens) {
        getBalance(tenantId).used.set(usedTokens);
    }
}
