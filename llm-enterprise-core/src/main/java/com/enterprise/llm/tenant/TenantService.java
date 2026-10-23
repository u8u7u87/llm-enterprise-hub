package com.enterprise.llm.tenant;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TenantService {

    private final TenantStorageProvider tenantStorageProvider;

    public TenantService(TenantStorageProvider tenantStorageProvider) {
        this.tenantStorageProvider = tenantStorageProvider;
    }

    public void registerTenant(TenantEntity tenant) {
        tenantStorageProvider.save(tenant);
    }

    public Optional<TenantEntity> authenticateApiKey(String apiKey) {
        return tenantStorageProvider.findByApiKey(apiKey);
    }

    public void recordTokenUsage(String tenantId, long tokens) {
        tenantStorageProvider.incrementUsedTokens(tenantId, tokens);
    }
}
