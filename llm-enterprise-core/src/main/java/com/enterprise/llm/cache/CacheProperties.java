package com.enterprise.llm.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm.cache")
public class CacheProperties {

    private boolean enabled = true;
    private int maxCapacity = 1000;
    private long ttlSeconds = 3600;
    private double semanticThreshold = 0.88;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public double getSemanticThreshold() {
        return semanticThreshold;
    }

    public void setSemanticThreshold(double semanticThreshold) {
        this.semanticThreshold = semanticThreshold;
    }
}
