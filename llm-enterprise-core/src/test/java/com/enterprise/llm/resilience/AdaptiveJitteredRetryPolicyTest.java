package com.enterprise.llm.resilience;

import com.enterprise.llm.exception.LlmAuthenticationException;
import com.enterprise.llm.exception.LlmRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveJitteredRetryPolicyTest {

    private AdaptiveJitteredRetryPolicy retryPolicy;

    @BeforeEach
    void setUp() {
        retryPolicy = new AdaptiveJitteredRetryPolicy(3, 100, 2000, AdaptiveJitteredRetryPolicy.JitterStrategy.FULL_JITTER);
    }

    @Test
    @DisplayName("Should determine retriability based on exception type and retry attempts")
    void shouldDetermineRetriability() {
        // Attempt 1: 429 Rate Limit is retriable
        assertThat(retryPolicy.shouldRetry(new LlmRateLimitException("Rate limited"), 1)).isTrue();

        // Attempt 1: Transient IO exception is retriable
        assertThat(retryPolicy.shouldRetry(new IOException("Connection reset"), 1)).isTrue();

        // Attempt 1: Auth failure (401) is permanent, NOT retriable
        assertThat(retryPolicy.shouldRetry(new LlmAuthenticationException("Bad token", 401, "invalid_api_key"), 1)).isFalse();

        // Attempt 4: Exceeds maxRetries (3), should not retry
        assertThat(retryPolicy.shouldRetry(new LlmRateLimitException("Rate limited"), 4)).isFalse();
    }

    @Test
    @DisplayName("Full Jitter backoff should be bounded within [0, min(cap, base * 2^attempt)]")
    void shouldComputeBoundedFullJitterBackoff() {
        for (int attempt = 0; attempt < 5; attempt++) {
            long backoff = retryPolicy.computeBackoffMs(attempt);
            long ceiling = Math.min(2000L, (long) (100 * Math.pow(2, attempt)));
            assertThat(backoff).isBetween(0L, ceiling);
        }
    }

    @Test
    @DisplayName("Equal Jitter backoff should have a deterministic floor of half backoff ceiling")
    void shouldComputeEqualJitterBackoff() {
        AdaptiveJitteredRetryPolicy equalJitterPolicy = new AdaptiveJitteredRetryPolicy(
                3, 200, 5000, AdaptiveJitteredRetryPolicy.JitterStrategy.EQUAL_JITTER
        );

        long backoff = equalJitterPolicy.computeBackoffMs(1); // attempt 1 -> ceiling 400 -> floor = 200, range [200, 400]
        assertThat(backoff).isBetween(200L, 400L);
    }
}
