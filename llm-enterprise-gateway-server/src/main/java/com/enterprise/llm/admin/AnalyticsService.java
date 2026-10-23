package com.enterprise.llm.admin;

import com.enterprise.llm.cache.CacheEngine;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalyticsService {

    private final TenantRepository tenantRepository;
    private final CacheEngine cacheEngine;

    public AnalyticsService(TenantRepository tenantRepository, CacheEngine cacheEngine) {
        this.tenantRepository = tenantRepository;
        this.cacheEngine = cacheEngine;
    }

    public AdminAnalyticsOverview getOverview() {
        List<TenantEntity> tenants = tenantRepository.findAll();
        long totalTenants = tenants.size();
        long activeTenants = tenants.stream().filter(TenantEntity::isEnabled).count();
        long totalTokensUsed = tenants.stream().mapToLong(TenantEntity::getUsedTokens).sum();

        long l1Hits = cacheEngine != null ? cacheEngine.getL1Hits() : 0;
        long l2Hits = cacheEngine != null ? cacheEngine.getL2Hits() : 0;
        long misses = cacheEngine != null ? cacheEngine.getMisses() : 0;
        long savedTokens = cacheEngine != null ? cacheEngine.getSavedTokens() : 0;

        long totalQueries = l1Hits + l2Hits + misses;
        double hitRate = totalQueries > 0 ? (double) (l1Hits + l2Hits) / totalQueries : 0.0;

        return new AdminAnalyticsOverview(
                totalTenants,
                activeTenants,
                totalTokensUsed,
                savedTokens,
                l1Hits,
                l2Hits,
                misses,
                Math.round(hitRate * 1000.0) / 10.0 // percentage e.g. 75.5%
        );
    }
}
