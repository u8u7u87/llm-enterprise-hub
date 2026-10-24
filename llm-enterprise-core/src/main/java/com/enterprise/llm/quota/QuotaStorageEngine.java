package com.enterprise.llm.quota;

/**
 * Storage SPI for managing atomic token quota reservation, settlement, and state.
 * Supports in-memory, local DB, or distributed Redis-backed quota engines.
 */
public interface QuotaStorageEngine {

    /**
     * Atomically check and reserve (freeze) tokens for a tenant.
     *
     * @param tenantId        the tenant identifier
     * @param estimatedTokens tokens required for this request
     * @param dailyQuota      daily quota threshold (<= 0 implies unlimited)
     * @return true if reservation granted, false if quota exceeded
     */
    boolean tryReserve(String tenantId, int estimatedTokens, long dailyQuota);

    /**
     * Atomically settle tokens: unfreeze estimatedTokens and increment usedTokens by actualTokens.
     */
    void settle(String tenantId, int estimatedTokens, int actualTokens);

    /**
     * Atomically release previously frozen tokens on request failure/cancellation.
     */
    void release(String tenantId, int estimatedTokens);

    /**
     * Get currently consumed tokens for today's cycle.
     */
    long getUsedTokens(String tenantId);

    /**
     * Get currently frozen (in-flight reserved) tokens.
     */
    long getFrozenTokens(String tenantId);

    /**
     * Reset quota usage for a tenant (e.g. for testing or new billing cycle).
     */
    void reset(String tenantId);
}
