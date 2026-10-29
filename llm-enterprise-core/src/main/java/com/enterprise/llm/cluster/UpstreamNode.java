package com.enterprise.llm.cluster;

import com.enterprise.llm.resilience.CircuitBreaker;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents an active upstream inference node in a remote GPU cluster (e.g., vLLM or Triton worker).
 */
public class UpstreamNode {

    private final String id;
    private final String baseUrl;
    private final String apiKey;
    private final ApiKeyPool apiKeyPool;
    private final int weight;
    private final AtomicInteger activeRequests;
    private final AtomicBoolean healthy;
    private final CircuitBreaker circuitBreaker;
    private final Set<String> supportedModels;
    private final Map<String, String> metadata;
    private volatile String engineType = "direct";
    private final AtomicInteger waitingRequests = new AtomicInteger(0);
    private volatile double gpuCacheUsage = 0.0;

    public UpstreamNode(String id, String baseUrl, String apiKey, int weight) {
        this.id = id;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey != null ? apiKey : "";
        this.apiKeyPool = ApiKeyPool.of(this.apiKey);
        this.weight = Math.max(1, weight);
        this.activeRequests = new AtomicInteger(0);
        this.healthy = new AtomicBoolean(true);
        this.circuitBreaker = new CircuitBreaker(id);
        this.supportedModels = ConcurrentHashMap.newKeySet();
        this.metadata = new ConcurrentHashMap<>();
    }

    public UpstreamNode(String id, String baseUrl, ApiKeyPool apiKeyPool, int weight) {
        this.id = id;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKeyPool = apiKeyPool != null ? apiKeyPool : new ApiKeyPool(Collections.singletonList(""));
        this.apiKey = this.apiKeyPool.acquireKey().getKey();
        this.weight = Math.max(1, weight);
        this.activeRequests = new AtomicInteger(0);
        this.healthy = new AtomicBoolean(true);
        this.circuitBreaker = new CircuitBreaker(id);
        this.supportedModels = ConcurrentHashMap.newKeySet();
        this.metadata = new ConcurrentHashMap<>();
    }

    public UpstreamNode(String id, String baseUrl) {
        this(id, baseUrl, "", 10);
    }

    public String getId() {
        return id;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        if (apiKeyPool != null && !apiKeyPool.isEmpty()) {
            return apiKeyPool.acquireKey().getKey();
        }
        return apiKey;
    }

    public ApiKeyPool getApiKeyPool() {
        return apiKeyPool;
    }

    public int getWeight() {
        return weight;
    }

    public int getActiveRequests() {
        return activeRequests.get();
    }

    public void setActiveRequests(int count) {
        this.activeRequests.set(Math.max(0, count));
    }

    public int incrementActiveRequests() {
        return activeRequests.incrementAndGet();
    }

    public int decrementActiveRequests() {
        return activeRequests.updateAndGet(curr -> Math.max(0, curr - 1));
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public void setHealthy(boolean isHealthy) {
        this.healthy.set(isHealthy);
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public boolean isAvailable() {
        return healthy.get() && !circuitBreaker.isOpen();
    }

    public void recordSuccess() {
        circuitBreaker.recordSuccess();
    }

    public void recordFailure() {
        circuitBreaker.recordFailure();
    }

    public Set<String> getSupportedModels() {
        return Collections.unmodifiableSet(supportedModels);
    }

    public void addSupportedModel(String model) {
        if (model != null && !model.isBlank()) {
            this.supportedModels.add(model.trim());
        }
    }

    public void setSupportedModels(Set<String> models) {
        this.supportedModels.clear();
        if (models != null) {
            this.supportedModels.addAll(models);
        }
    }

    public boolean supportsModel(String model) {
        if (model == null || supportedModels.isEmpty()) {
            return true; // if empty, assumes wildcard
        }
        if (supportedModels.contains(model)) {
            return true;
        }
        for (String pattern : supportedModels) {
            if (pattern.endsWith("*") && model.startsWith(pattern.substring(0, pattern.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    public String getEngineType() {
        return engineType;
    }

    public void setEngineType(String engineType) {
        if (engineType != null && !engineType.isBlank()) {
            this.engineType = engineType.trim().toLowerCase();
        }
    }

    public int getWaitingRequests() {
        return waitingRequests.get();
    }

    public void setWaitingRequests(int count) {
        this.waitingRequests.set(Math.max(0, count));
    }

    public int incrementWaitingRequests() {
        return waitingRequests.incrementAndGet();
    }

    public int decrementWaitingRequests() {
        return waitingRequests.updateAndGet(curr -> Math.max(0, curr - 1));
    }

    public double getGpuCacheUsage() {
        return gpuCacheUsage;
    }

    public void setGpuCacheUsage(double gpuCacheUsage) {
        this.gpuCacheUsage = Math.max(0.0, Math.min(1.0, gpuCacheUsage));
    }

    public boolean isOverloaded(int maxWaitingThreshold, double maxGpuCacheThreshold) {
        if (maxWaitingThreshold > 0 && waitingRequests.get() >= maxWaitingThreshold) {
            return true;
        }
        if (maxGpuCacheThreshold > 0.0 && gpuCacheUsage >= maxGpuCacheThreshold) {
            return true;
        }
        return false;
    }

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public void putMetadata(String key, String value) {
        this.metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "UpstreamNode{" +
                "id='" + id + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", healthy=" + healthy.get() +
                ", activeRequests=" + activeRequests.get() +
                ", cbState=" + circuitBreaker.getState() +
                '}';
    }
}
