package com.enterprise.llm.backend;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for pluggable inference backend engine selection.
 */
@ConfigurationProperties(prefix = "llm.backend")
public class InferenceBackendProperties {

    /**
     * Active backend inference engine: direct (default), vllm, sglang, triton, ray, or custom.
     */
    private String engine = "direct";

    /**
     * Maximum waiting requests threshold for backpressure overload protection (0 to disable).
     */
    private int maxWaitingRequests = 20;

    /**
     * Maximum GPU cache usage threshold (e.g. 0.95 = 95%) before overload protection triggers (0.0 to disable).
     */
    private double maxGpuCacheUsage = 0.95;

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        if (engine != null && !engine.isBlank()) {
            this.engine = engine.trim().toLowerCase();
        }
    }

    public int getMaxWaitingRequests() {
        return maxWaitingRequests;
    }

    public void setMaxWaitingRequests(int maxWaitingRequests) {
        this.maxWaitingRequests = maxWaitingRequests;
    }

    public double getMaxGpuCacheUsage() {
        return maxGpuCacheUsage;
    }

    public void setMaxGpuCacheUsage(double maxGpuCacheUsage) {
        this.maxGpuCacheUsage = maxGpuCacheUsage;
    }
}
