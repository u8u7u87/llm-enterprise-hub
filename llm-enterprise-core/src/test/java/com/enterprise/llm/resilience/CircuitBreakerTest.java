package com.enterprise.llm.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private CircuitBreakerConfig config;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        // Fast cooldown for deterministic testing: 100ms cooldown, 3 failures to open, 1 success to heal
        config = new CircuitBreakerConfig(3, 100L, 1, 1);
        circuitBreaker = new CircuitBreaker("test-breaker", config);
    }

    @Test
    @DisplayName("Initial state should be CLOSED and allow requests")
    void initialStateShouldBeClosed() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
    }

    @Test
    @DisplayName("Should trip from CLOSED to OPEN after failureThreshold reached")
    void shouldTransitionToOpenOnConsecutiveFailures() {
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);

        circuitBreaker.recordFailure(); // 3rd failure reaches threshold
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

        // While OPEN, requests must be rejected immediately
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    @DisplayName("Should transition from OPEN to HALF_OPEN after cooldown and allow probe request")
    void shouldTransitionToHalfOpenAfterCooldown() throws InterruptedException {
        // Trip to OPEN
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();

        // Wait for cooldown (100ms + margin)
        Thread.sleep(120);

        // State should transition to HALF_OPEN when checked or acquired
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        // Since halfOpenMaxPermits = 1, subsequent concurrent calls in HALF_OPEN should be rejected
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    @DisplayName("Should self-heal from HALF_OPEN to CLOSED on probe success")
    void shouldSelfHealToClosedOnProbeSuccess() throws InterruptedException {
        // Trip to OPEN
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

        // Cooldown
        Thread.sleep(120);

        // Acquire probe permit
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        // Probe succeeds
        circuitBreaker.recordSuccess();

        // Breaker should be self-healed back to CLOSED
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
    }

    @Test
    @DisplayName("Should return to OPEN from HALF_OPEN if probe fails")
    void shouldReopenIfProbeFailsInHalfOpen() throws InterruptedException {
        // Trip to OPEN
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);

        // Cooldown
        Thread.sleep(120);

        // Acquire probe permit
        assertThat(circuitBreaker.tryAcquirePermission()).isTrue();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.HALF_OPEN);

        // Probe fails
        circuitBreaker.recordFailure();

        // Breaker must return to OPEN
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(circuitBreaker.tryAcquirePermission()).isFalse();
    }

    @Test
    @DisplayName("Concurrent requests in CLOSED should maintain atomic state")
    void shouldHandleConcurrentRequestsSafely() throws InterruptedException {
        int threads = 20;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger totalFailures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (circuitBreaker.tryAcquirePermission()) {
                            circuitBreaker.recordSuccess();
                        } else {
                            totalFailures.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreakerState.CLOSED);
    }
}
