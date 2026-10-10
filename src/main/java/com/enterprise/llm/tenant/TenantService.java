package com.enterprise.llm.tenant;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public void registerTenant(TenantEntity tenant) {
        tenantRepository.save(tenant);
    }

    public Optional<TenantEntity> authenticateApiKey(String apiKey) {
        return tenantRepository.findByApiKey(apiKey);
    }

    public void recordTokenUsage(String tenantId, long tokens) {
        tenantRepository.incrementUsedTokens(tenantId, tokens);
    }
}
