package com.enterprise.llm.tenant;

import java.util.List;
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
}
