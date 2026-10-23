package com.enterprise.llm.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fallback implementation of {@link TenantStorageProvider}.
 * Ideal for lightweight testing, embedded SDK use, or standalone microservices without database dependencies.
 */
public class InMemoryTenantStorageProvider implements TenantStorageProvider {

    private final Map<String, TenantEntity> storage = new ConcurrentHashMap<>();

    @Override
    public void save(TenantEntity tenant) {
        if (tenant != null && tenant.getId() != null) {
            storage.put(tenant.getId(), tenant);
        }
    }

    @Override
    public Optional<TenantEntity> findById(String id) {
        return id != null ? Optional.ofNullable(storage.get(id)) : Optional.empty();
    }

    @Override
    public Optional<TenantEntity> findByApiKey(String apiKey) {
        if (apiKey == null) {
            return Optional.empty();
        }
        return storage.values().stream()
                .filter(t -> apiKey.equals(t.getApiKey()))
                .findFirst();
    }

    @Override
    public void incrementUsedTokens(String id, long tokens) {
        TenantEntity entity = storage.get(id);
        if (entity != null) {
            entity.setUsedTokens(entity.getUsedTokens() + tokens);
        }
    }

    @Override
    public List<TenantEntity> findAll() {
        return new ArrayList<>(storage.values());
    }

    @Override
    public void updateQuota(String id, long dailyQuota, Long usedTokens) {
        TenantEntity entity = storage.get(id);
        if (entity != null) {
            entity.setDailyTokenQuota(dailyQuota);
            if (usedTokens != null) {
                entity.setUsedTokens(usedTokens);
            }
        }
    }

    @Override
    public void updateEnabled(String id, boolean enabled) {
        TenantEntity entity = storage.get(id);
        if (entity != null) {
            entity.setEnabled(enabled);
        }
    }

    @Override
    public void deleteAll() {
        storage.clear();
    }
}
