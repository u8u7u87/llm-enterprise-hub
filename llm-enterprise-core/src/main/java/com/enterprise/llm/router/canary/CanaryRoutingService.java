package com.enterprise.llm.router.canary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enterprise Canary Routing Engine.
 * Supports weighted traffic splitting, sticky session/tenant consistent hashing,
 * and tenant-level VIP canary overrides for safe zero-downtime model rollouts.
 */
@Service
public class CanaryRoutingService {

    private static final Logger log = LoggerFactory.getLogger(CanaryRoutingService.class);

    // Global canary rules: baseModel -> CanaryRule
    private final Map<String, CanaryRule> globalRules = new ConcurrentHashMap<>();

    // Tenant overrides: "tenantId:baseModel" -> targetModel
    private final Map<String, String> tenantOverrides = new ConcurrentHashMap<>();

    public void setRule(CanaryRule rule) {
        if (rule != null && rule.baseModel() != null) {
            globalRules.put(rule.baseModel(), rule);
            log.info("Registered canary rule: {} -> {} (weight={}% stickyTenant={} stickySess={})",
                    rule.baseModel(), rule.canaryModel(), rule.weightPercent(),
                    rule.stickyByTenant(), rule.stickyBySession());
        }
    }

    public void removeRule(String baseModel) {
        if (baseModel != null) {
            globalRules.remove(baseModel);
            log.info("Removed canary rule for base model: {}", baseModel);
        }
    }

    public void setTenantOverride(String tenantId, String baseModel, String targetModel) {
        if (tenantId != null && baseModel != null && targetModel != null) {
            tenantOverrides.put(tenantId + ":" + baseModel, targetModel);
            log.info("Set tenant canary override: tenant={}, baseModel={} -> {}", tenantId, baseModel, targetModel);
        }
    }

    public void removeTenantOverride(String tenantId, String baseModel) {
        if (tenantId != null && baseModel != null) {
            tenantOverrides.remove(tenantId + ":" + baseModel);
        }
    }

    /**
     * Resolves the target model for a given request considering tenant overrides,
     * sticky hashing, and canary weight distribution.
     */
    public String resolveModel(String requestedModel, String tenantId, String sessionId) {
        if (requestedModel == null) {
            return null;
        }

        // 1. Check tenant explicit override
        if (tenantId != null) {
            String override = tenantOverrides.get(tenantId + ":" + requestedModel);
            if (override != null) {
                return override;
            }
        }

        // 2. Check global canary rule
        CanaryRule rule = globalRules.get(requestedModel);
        if (rule == null) {
            return requestedModel;
        }

        if (rule.weightPercent() <= 0) {
            return rule.baseModel();
        }
        if (rule.weightPercent() >= 100) {
            return rule.canaryModel();
        }

        // 3. Evaluate stickiness
        int bucket;
        if (rule.stickyBySession() && sessionId != null && !sessionId.isBlank()) {
            bucket = Math.abs(sessionId.hashCode()) % 100;
        } else if (rule.stickyByTenant() && tenantId != null && !tenantId.isBlank()) {
            bucket = Math.abs(tenantId.hashCode()) % 100;
        } else {
            bucket = ThreadLocalRandom.current().nextInt(100);
        }

        if (bucket < rule.weightPercent()) {
            log.debug("Canary hit! Routed {} -> {} (bucket={}, weight={})",
                    requestedModel, rule.canaryModel(), bucket, rule.weightPercent());
            return rule.canaryModel();
        }

        return rule.baseModel();
    }
}
