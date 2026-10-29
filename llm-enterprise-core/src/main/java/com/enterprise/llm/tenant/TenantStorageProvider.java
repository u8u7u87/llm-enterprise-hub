package com.enterprise.llm.tenant;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage SPI for Tenant metadata and token quota persistence.
 * Implementations may target SQLite, JPA (PostgreSQL/MySQL), or Redis.
 */
public interface TenantStorageProvider {

    void save(TenantEntity tenant);

    Optional<TenantEntity> findById(String id);

    Optional<TenantEntity> findByApiKey(String apiKey);

    void incrementUsedTokens(String id, long tokens);

    List<TenantEntity> findAll();

    void updateQuota(String id, long dailyQuota, Long usedTokens);

    void updateEnabled(String id, boolean enabled);

    void deleteAll();

    /**
     * Batch increment used tokens for multiple tenants in a single batch operation.
     * Prevents database hot row contention in high-concurrency environments.
     */
    default void batchIncrementUsedTokens(Map<String, Long> tokenDeltas) {
        if (tokenDeltas != null) {
            tokenDeltas.forEach((id, delta) -> {
                if (delta != null && delta > 0) {
                    incrementUsedTokens(id, delta);
                }
            });
        }
    }
}
