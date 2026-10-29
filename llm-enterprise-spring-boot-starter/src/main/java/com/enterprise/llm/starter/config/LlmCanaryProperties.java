package com.enterprise.llm.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for canary (weighted) model rollouts, bound from {@code llm.canary}.
 *
 * <p>Rules are registered into the {@code CanaryRoutingService} bean at startup. When
 * {@code enabled} is false nothing is registered, so {@code resolveModel} returns the requested
 * model unchanged and the gateway behaves exactly as if canary routing did not exist.
 */
@ConfigurationProperties(prefix = "llm.canary")
public class LlmCanaryProperties {

    private boolean enabled = false;
    private List<Rule> rules = new ArrayList<>();
    private List<TenantOverride> overrides = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = (rules != null) ? rules : new ArrayList<>();
    }

    public List<TenantOverride> getOverrides() {
        return overrides;
    }

    public void setOverrides(List<TenantOverride> overrides) {
        this.overrides = (overrides != null) ? overrides : new ArrayList<>();
    }

    public static class Rule {
        private String baseModel;
        private String canaryModel;
        private int weightPercent = 0;
        private boolean stickyByTenant = true;
        private boolean stickyBySession = false;

        public String getBaseModel() {
            return baseModel;
        }

        public void setBaseModel(String baseModel) {
            this.baseModel = baseModel;
        }

        public String getCanaryModel() {
            return canaryModel;
        }

        public void setCanaryModel(String canaryModel) {
            this.canaryModel = canaryModel;
        }

        public int getWeightPercent() {
            return weightPercent;
        }

        public void setWeightPercent(int weightPercent) {
            this.weightPercent = weightPercent;
        }

        public boolean isStickyByTenant() {
            return stickyByTenant;
        }

        public void setStickyByTenant(boolean stickyByTenant) {
            this.stickyByTenant = stickyByTenant;
        }

        public boolean isStickyBySession() {
            return stickyBySession;
        }

        public void setStickyBySession(boolean stickyBySession) {
            this.stickyBySession = stickyBySession;
        }
    }

    public static class TenantOverride {
        private String tenantId;
        private String baseModel;
        private String targetModel;

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getBaseModel() {
            return baseModel;
        }

        public void setBaseModel(String baseModel) {
            this.baseModel = baseModel;
        }

        public String getTargetModel() {
            return targetModel;
        }

        public void setTargetModel(String targetModel) {
            this.targetModel = targetModel;
        }
    }
}
