package com.enterprise.llm.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生产级三态自愈熔断器 (Stateful Circuit Breaker)
 * 具备 CLOSED -> OPEN -> HALF_OPEN 完备状态跃迁与探针并发控制，零外部第三方框架强依赖。
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String name;
    private final CircuitBreakerConfig config;

    private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastStateChangeTimestamp = new AtomicLong(System.currentTimeMillis());

    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenActivePermits = new AtomicInteger(0);

    public CircuitBreaker(String name, CircuitBreakerConfig config) {
        this.name = name != null ? name : "default-breaker";
        this.config = config != null ? config : CircuitBreakerConfig.defaultConfig();
    }

    public CircuitBreaker(String name) {
        this(name, CircuitBreakerConfig.defaultConfig());
    }

    public String getName() {
        return name;
    }

    public CircuitBreakerConfig getConfig() {
        return config;
    }

    /**
     * 获取当前状态（若在 OPEN 且已过冷却期，自动惰性计算跃迁至 HALF_OPEN）
     */
    public CircuitBreakerState getState() {
        checkAndTransitionIfCooldownElapsed();
        return state.get();
    }

    /**
     * 尝试获取执行许可
     *
     * @return true 允许访问主服务；false 熔断拦截，应快速失败或走降级备用服务
     */
    public boolean tryAcquirePermission() {
        checkAndTransitionIfCooldownElapsed();
        CircuitBreakerState currentState = state.get();

        if (currentState == CircuitBreakerState.CLOSED) {
            return true;
        }

        if (currentState == CircuitBreakerState.OPEN) {
            return false;
        }

        if (currentState == CircuitBreakerState.HALF_OPEN) {
            int currentPermits = halfOpenActivePermits.incrementAndGet();
            if (currentPermits <= config.halfOpenMaxPermits()) {
                log.info("CircuitBreaker [{}]: Probe permit [{}/{}] granted in HALF_OPEN state",
                        name, currentPermits, config.halfOpenMaxPermits());
                return true;
            } else {
                return false;
            }
        }

        return false;
    }

    /**
     * 记录一次调用成功
     */
    public void recordSuccess() {
        CircuitBreakerState currentState = state.get();

        if (currentState == CircuitBreakerState.HALF_OPEN) {
            int successes = halfOpenSuccessCount.incrementAndGet();
            if (successes >= config.halfOpenSuccessThreshold()) {
                if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
                    failureCount.set(0);
                    halfOpenSuccessCount.set(0);
                    halfOpenActivePermits.set(0);
                    lastStateChangeTimestamp.set(System.currentTimeMillis());
                    log.info("CircuitBreaker [{}]: Probe succeeded. Circuit breaker self-healed and transitioned to CLOSED", name);
                }
            }
        } else if (currentState == CircuitBreakerState.CLOSED) {
            failureCount.set(0);
        }
    }

    /**
     * 记录一次调用失败
     */
    public void recordFailure() {
        CircuitBreakerState currentState = state.get();

        if (currentState == CircuitBreakerState.HALF_OPEN) {
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
                lastStateChangeTimestamp.set(System.currentTimeMillis());
                halfOpenSuccessCount.set(0);
                halfOpenActivePermits.set(0);
                log.warn("CircuitBreaker [{}]: Probe failed in HALF_OPEN. Returned to OPEN state with renewed cooldown of {}ms",
                        name, config.cooldownDurationMillis());
            }
        } else if (currentState == CircuitBreakerState.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= config.failureThreshold()) {
                if (state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)) {
                    lastStateChangeTimestamp.set(System.currentTimeMillis());
                    log.warn("CircuitBreaker [{}]: Failure threshold reached ({}/{}). Transitioned to OPEN state",
                            name, failures, config.failureThreshold());
                }
            }
        }
    }

    /**
     * 强制重置到闭合正常状态
     */
    public void reset() {
        state.set(CircuitBreakerState.CLOSED);
        failureCount.set(0);
        halfOpenSuccessCount.set(0);
        halfOpenActivePermits.set(0);
        lastStateChangeTimestamp.set(System.currentTimeMillis());
        log.info("CircuitBreaker [{}]: Manually reset to CLOSED", name);
    }

    private void checkAndTransitionIfCooldownElapsed() {
        if (state.get() == CircuitBreakerState.OPEN) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastStateChangeTimestamp.get();
            if (elapsed >= config.cooldownDurationMillis()) {
                if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                    halfOpenSuccessCount.set(0);
                    halfOpenActivePermits.set(0);
                    lastStateChangeTimestamp.set(now);
                    log.info("CircuitBreaker [{}]: Cooldown elapsed ({}ms >= {}ms). Transitioned from OPEN to HALF_OPEN",
                            name, elapsed, config.cooldownDurationMillis());
                }
            }
        }
    }
}
