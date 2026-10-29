package com.enterprise.llm.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enterprise Multi-Key Pool with Rate-Limit Auto-Cooling and Smooth Rotation.
 * Supports round-robin credential leasing, automated 429 backoff cooling,
 * and transparent fallback across multiple API Keys.
 */
public class ApiKeyPool {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyPool.class);

    private final List<ApiKeyEntry> entries;
    private final AtomicInteger roundRobinIndex;

    public ApiKeyPool(List<String> rawKeys) {
        this.entries = new CopyOnWriteArrayList<>();
        this.roundRobinIndex = new AtomicInteger(0);

        if (rawKeys != null) {
            for (String k : rawKeys) {
                if (k != null && !k.trim().isEmpty()) {
                    entries.add(new ApiKeyEntry(k.trim()));
                }
            }
        }

        // Guarantee at least one entry exists
        if (entries.isEmpty()) {
            entries.add(new ApiKeyEntry(""));
        }
    }

    public static ApiKeyPool of(String commaSeparatedOrSingleKey) {
        if (commaSeparatedOrSingleKey == null || commaSeparatedOrSingleKey.isBlank()) {
            return new ApiKeyPool(Collections.singletonList(""));
        }
        String[] parts = commaSeparatedOrSingleKey.split(",");
        List<String> keys = new ArrayList<>();
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                keys.add(p.trim());
            }
        }
        return new ApiKeyPool(keys);
    }

    /**
     * Leases an active, non-cooling API Key.
     * Uses round-robin among available keys.
     * If all keys are currently cooling down, returns the one with the shortest remaining cooldown.
     */
    public ApiKeyEntry acquireKey() {
        if (entries.isEmpty()) {
            return new ApiKeyEntry("");
        }

        long now = System.currentTimeMillis();
        int total = entries.size();
        int startIndex = Math.abs(roundRobinIndex.getAndIncrement() % total);

        // First pass: find an available key starting from round-robin index
        for (int i = 0; i < total; i++) {
            ApiKeyEntry entry = entries.get((startIndex + i) % total);
            if (entry.isAvailable(now)) {
                return entry;
            }
        }

        // Second pass: all keys in cooldown, choose the one with the earliest cooldown expiry
        ApiKeyEntry bestCandidate = entries.get(0);
        long minRemaining = Long.MAX_VALUE;
        for (ApiKeyEntry entry : entries) {
            long remaining = entry.getRemainingCooldownMs(now);
            if (remaining < minRemaining) {
                minRemaining = remaining;
                bestCandidate = entry;
            }
        }

        log.warn("All {} API keys in pool are currently in cooldown. Selecting key with shortest remaining wait: {} ms",
                total, minRemaining);
        return bestCandidate;
    }

    /**
     * Records a rate-limit (HTTP 429) event for a specific key, putting it into cooldown.
     *
     * @param key             the API key
     * @param cooldownSeconds cooldown duration in seconds
     */
    public void recordRateLimit(String key, long cooldownSeconds) {
        recordRateLimitMillis(key, cooldownSeconds * 1000L);
    }

    public void recordRateLimitMillis(String key, long cooldownMillis) {
        for (ApiKeyEntry entry : entries) {
            if (entry.getKey().equals(key)) {
                entry.setCooldown(cooldownMillis);
                log.warn("API Key [...] rate-limited (429), entered cooldown for {} ms", cooldownMillis);
                break;
            }
        }
    }

    /**
     * Records a successful request for a key, resetting its failure count.
     */
    public void recordSuccess(String key) {
        for (ApiKeyEntry entry : entries) {
            if (entry.getKey().equals(key)) {
                entry.recordSuccess();
                break;
            }
        }
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int getAvailableCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (ApiKeyEntry entry : entries) {
            if (entry.isAvailable(now)) {
                count++;
            }
        }
        return count;
    }

    public List<ApiKeyEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }
}
