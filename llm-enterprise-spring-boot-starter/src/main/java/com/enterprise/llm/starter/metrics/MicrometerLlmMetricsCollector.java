package com.enterprise.llm.starter.metrics;

import com.enterprise.llm.metrics.LlmMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production-ready Micrometer-based implementation of {@link LlmMetricsCollector}.
 * Automatically registers and updates metrics in the Spring {@link MeterRegistry}
 * for scraping by Prometheus or other APM backends.
 */
public class MicrometerLlmMetricsCollector implements LlmMetricsCollector {

    public static final String METRIC_REQUESTS_TOTAL = "llm.requests.total";
    public static final String METRIC_REQUEST_DURATION = "llm.request.duration";
    public static final String METRIC_TTFT = "llm.time.to.first.token";
    public static final String METRIC_TPS = "llm.tokens.per.second";
    public static final String METRIC_TOKENS_TOTAL = "llm.tokens.total";
    public static final String METRIC_TOOL_DURATION = "llm.tool.duration";
    public static final String METRIC_ACTIVE_REQUESTS = "llm.active.requests";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> activeRequestsByModel = new ConcurrentHashMap<>();

    public MicrometerLlmMetricsCollector(MeterRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("MeterRegistry must not be null");
        }
        this.registry = registry;
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    @Override
    public void recordRequest(String model,
                             String tenantId,
                             String status,
                             long durationMs,
                             Long ttftMs,
                             Double tps,
                             int promptTokens,
                             int completionTokens) {
        String safeModel = safeTag(model, "unknown");
        String safeTenant = safeTag(tenantId, "default");
        String safeStatus = safeTag(status, "unknown");

        // 1. Total requests counter
        Counter.builder(METRIC_REQUESTS_TOTAL)
                .tag("model", safeModel)
                .tag("tenant", safeTenant)
                .tag("status", safeStatus)
                .description("Total number of LLM inference requests")
                .register(registry)
                .increment();

        // 2. Request duration timer
        if (durationMs >= 0) {
            Timer.builder(METRIC_REQUEST_DURATION)
                    .tag("model", safeModel)
                    .tag("tenant", safeTenant)
                    .description("Total request latency in milliseconds")
                    .publishPercentiles(0.5, 0.9, 0.99)
                    .register(registry)
                    .record(Duration.ofMillis(durationMs));
        }

        // 3. Time To First Token (TTFT) timer
        if (ttftMs != null && ttftMs >= 0) {
            Timer.builder(METRIC_TTFT)
                    .tag("model", safeModel)
                    .tag("tenant", safeTenant)
                    .description("Time to first token (streaming TTFT) in milliseconds")
                    .publishPercentiles(0.5, 0.9, 0.99)
                    .register(registry)
                    .record(Duration.ofMillis(ttftMs));
        }

        // 4. Tokens per second summary
        if (tps != null && tps > 0) {
            DistributionSummary.builder(METRIC_TPS)
                    .tag("model", safeModel)
                    .description("Token generation throughput (tokens/sec)")
                    .publishPercentiles(0.5, 0.9, 0.99)
                    .register(registry)
                    .record(tps);
        }

        // 5. Token counters
        if (promptTokens > 0) {
            Counter.builder(METRIC_TOKENS_TOTAL)
                    .tag("model", safeModel)
                    .tag("tenant", safeTenant)
                    .tag("type", "prompt")
                    .description("Total consumed prompt and completion tokens")
                    .register(registry)
                    .increment(promptTokens);
        }
        if (completionTokens > 0) {
            Counter.builder(METRIC_TOKENS_TOTAL)
                    .tag("model", safeModel)
                    .tag("tenant", safeTenant)
                    .tag("type", "completion")
                    .description("Total consumed prompt and completion tokens")
                    .register(registry)
                    .increment(completionTokens);
        }
    }

    @Override
    public void recordToolExecution(String toolName, String tenantId, String status, long durationMs) {
        String safeTool = safeTag(toolName, "unknown");
        String safeTenant = safeTag(tenantId, "default");
        String safeStatus = safeTag(status, "unknown");

        if (durationMs >= 0) {
            Timer.builder(METRIC_TOOL_DURATION)
                    .tag("tool", safeTool)
                    .tag("tenant", safeTenant)
                    .tag("status", safeStatus)
                    .description("Tool execution latency in milliseconds")
                    .publishPercentiles(0.5, 0.9, 0.99)
                    .register(registry)
                    .record(Duration.ofMillis(durationMs));
        }
    }

    @Override
    public void recordActiveRequest(String model, int delta) {
        String safeModel = safeTag(model, "unknown");
        AtomicInteger counter = activeRequestsByModel.computeIfAbsent(safeModel, m -> {
            AtomicInteger ai = new AtomicInteger(0);
            registry.gauge(METRIC_ACTIVE_REQUESTS, Tags.of("model", m), ai, AtomicInteger::get);
            return ai;
        });

        int newVal = counter.addAndGet(delta);
        if (newVal < 0) {
            counter.set(0);
        }
    }

    private String safeTag(String value, String defaultValue) {
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }
}
