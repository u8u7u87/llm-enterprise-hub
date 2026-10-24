package com.enterprise.llm.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyPoolTest {

    @Test
    @DisplayName("Verify round-robin rotation across available API keys")
    void testRoundRobinRotation() {
        ApiKeyPool pool = ApiKeyPool.of("sk-key1,sk-key2,sk-key3");
        assertThat(pool.size()).isEqualTo(3);
        assertThat(pool.getAvailableCount()).isEqualTo(3);

        ApiKeyEntry k1 = pool.acquireKey();
        ApiKeyEntry k2 = pool.acquireKey();
        ApiKeyEntry k3 = pool.acquireKey();
        ApiKeyEntry k4 = pool.acquireKey();

        assertThat(k1.getKey()).isEqualTo("sk-key1");
        assertThat(k2.getKey()).isEqualTo("sk-key2");
        assertThat(k3.getKey()).isEqualTo("sk-key3");
        assertThat(k4.getKey()).isEqualTo("sk-key1");
    }

    @Test
    @DisplayName("Verify rate-limited key is put into cooldown and skipped during rotation")
    void testRateLimitCooldown() {
        ApiKeyPool pool = ApiKeyPool.of("sk-key1,sk-key2");

        // Mark key1 as rate-limited for 60 seconds
        pool.recordRateLimit("sk-key1", 60);

        assertThat(pool.getAvailableCount()).isEqualTo(1);

        // Next acquires should only return key2
        ApiKeyEntry a1 = pool.acquireKey();
        ApiKeyEntry a2 = pool.acquireKey();

        assertThat(a1.getKey()).isEqualTo("sk-key2");
        assertThat(a2.getKey()).isEqualTo("sk-key2");
    }

    @Test
    @DisplayName("Verify key automatically recovers after cooldown expires")
    void testCooldownExpiry() throws InterruptedException {
        ApiKeyPool pool = ApiKeyPool.of("sk-key1");

        // Set short cooldown (100 ms)
        pool.recordRateLimitMillis("sk-key1", 100);
        assertThat(pool.getAvailableCount()).isEqualTo(0);

        Thread.sleep(150);

        assertThat(pool.getAvailableCount()).isEqualTo(1);
        assertThat(pool.acquireKey().getKey()).isEqualTo("sk-key1");
    }

    @Test
    @DisplayName("Verify fallback behavior when all keys are in cooldown")
    void testAllKeysInCooldown() {
        ApiKeyPool pool = ApiKeyPool.of("sk-key1,sk-key2");
        pool.recordRateLimit("sk-key1", 60);
        pool.recordRateLimit("sk-key2", 30);

        assertThat(pool.getAvailableCount()).isEqualTo(0);

        // Should return the key with shortest remaining cooldown (sk-key2)
        ApiKeyEntry entry = pool.acquireKey();
        assertThat(entry).isNotNull();
        assertThat(entry.getKey()).isEqualTo("sk-key2");
    }
}
