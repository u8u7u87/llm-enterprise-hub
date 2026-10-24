package com.enterprise.llm.cluster;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a single API credential entry with runtime cooldown and health state.
 */
public class ApiKeyEntry {

    private final String key;
    private final AtomicLong cooldownUntilMs;
    private final AtomicInteger failureCount;
    private final AtomicInteger successCount;

    public ApiKeyEntry(String key) {
        this.key = key != null ? key.trim() : "";
        this.cooldownUntilMs = new AtomicLong(0L);
        this.failureCount = new AtomicInteger(0);
        this.successCount = new AtomicInteger(0);
    }

    public String getKey() {
        return key;
    }

    public boolean isAvailable(long nowMs) {
        return nowMs >= cooldownUntilMs.get();
    }

    public long getCooldownUntilMs() {
        return cooldownUntilMs.get();
    }

    public long getRemainingCooldownMs(long nowMs) {
        long remaining = cooldownUntilMs.get() - nowMs;
        return Math.max(0L, remaining);
    }

    public void setCooldown(long cooldownMillis) {
        long now = System.currentTimeMillis();
        this.cooldownUntilMs.set(now + Math.max(0L, cooldownMillis));
        this.failureCount.incrementAndGet();
    }

    public void recordSuccess() {
        this.successCount.incrementAndGet();
        this.failureCount.set(0);
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    @Override
    public String toString() {
        String masked = (key != null && key.length() > 8)
                ? key.substring(0, 4) + "..." + key.substring(key.length() - 4)
                : "***";
        return "ApiKeyEntry{" +
                "key='" + masked + '\'' +
                ", cooldownUntil=" + cooldownUntilMs.get() +
                ", failures=" + failureCount.get() +
                '}';
    }
}
