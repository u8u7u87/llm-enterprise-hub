package com.enterprise.llm.resilience;

import com.enterprise.llm.exception.LlmAuthenticationException;
import com.enterprise.llm.exception.LlmContentFilterException;
import com.enterprise.llm.exception.LlmInvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enterprise Adaptive Jittered Retry Policy.
 * Implements Full Jitter and Equal Jitter exponential backoff algorithms (AWS/Envoy standard)
 * to desynchronize concurrent client retries and eliminate thundering herds on upstream LLM clusters.
 */
@Component
public class AdaptiveJitteredRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveJitteredRetryPolicy.class);

    public enum JitterStrategy {
        FULL_JITTER,
        EQUAL_JITTER,
        NONE
    }

    private final int maxRetries;
    private final long baseBackoffMs;
    private final long maxBackoffMs;
    private final JitterStrategy jitterStrategy;

    public AdaptiveJitteredRetryPolicy() {
        this(3, 100, 2000, JitterStrategy.FULL_JITTER);
    }

    public AdaptiveJitteredRetryPolicy(int maxRetries, long baseBackoffMs, long maxBackoffMs, JitterStrategy jitterStrategy) {
        this.maxRetries = Math.max(0, maxRetries);
        this.baseBackoffMs = Math.max(10, baseBackoffMs);
        this.maxBackoffMs = Math.max(this.baseBackoffMs, maxBackoffMs);
        this.jitterStrategy = jitterStrategy != null ? jitterStrategy : JitterStrategy.FULL_JITTER;
    }

    /**
     * Determines whether an operation should be retried based on exception type and attempt count.
     */
    public boolean shouldRetry(Throwable t, int attempt) {
        if (t == null || attempt > maxRetries) {
            return false;
        }

        // Permanent client/auth/security errors should never be retried
        if (t instanceof LlmAuthenticationException
                || t instanceof LlmContentFilterException
                || t instanceof LlmInvalidRequestException) {
            return false;
        }

        // Rate limit, connection timeout, network errors are retriable
        if (t instanceof com.enterprise.llm.exception.LlmRateLimitException
                || t instanceof IOException) {
            return true;
        }

        // Generic LlmException retryable check
        if (t instanceof com.enterprise.llm.exception.LlmException llmEx) {
            return llmEx.isRetryable();
        }

        return false;
    }

    /**
     * Computes the backoff duration in milliseconds for the given retry attempt.
     */
    public long computeBackoffMs(int attempt) {
        long ceiling = Math.min(maxBackoffMs, (long) (baseBackoffMs * Math.pow(2, attempt)));

        return switch (jitterStrategy) {
            case FULL_JITTER -> ThreadLocalRandom.current().nextLong(ceiling + 1);
            case EQUAL_JITTER -> {
                long half = ceiling / 2;
                yield half + ThreadLocalRandom.current().nextLong(half + 1);
            }
            case NONE -> ceiling;
        };
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getBaseBackoffMs() {
        return baseBackoffMs;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public JitterStrategy getJitterStrategy() {
        return jitterStrategy;
    }
}
