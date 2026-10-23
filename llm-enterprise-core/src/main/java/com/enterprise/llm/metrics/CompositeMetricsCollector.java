package com.enterprise.llm.metrics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Composite {@link LlmMetricsCollector} that broadcasts events to multiple underlying collectors.
 */
public class CompositeMetricsCollector implements LlmMetricsCollector {

    private final List<LlmMetricsCollector> collectors = new CopyOnWriteArrayList<>();

    public CompositeMetricsCollector() {
    }

    public CompositeMetricsCollector(List<LlmMetricsCollector> collectors) {
        if (collectors != null) {
            this.collectors.addAll(collectors);
        }
    }

    public void addCollector(LlmMetricsCollector collector) {
        if (collector != null && !(collector instanceof NoOpMetricsCollector)) {
            this.collectors.add(collector);
        }
    }

    public void removeCollector(LlmMetricsCollector collector) {
        this.collectors.remove(collector);
    }

    public List<LlmMetricsCollector> getCollectors() {
        return List.copyOf(collectors);
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
        for (LlmMetricsCollector collector : collectors) {
            try {
                collector.recordRequest(model, tenantId, status, durationMs, ttftMs, tps, promptTokens, completionTokens);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void recordToolExecution(String toolName,
                                    String tenantId,
                                    String status,
                                    long durationMs) {
        for (LlmMetricsCollector collector : collectors) {
            try {
                collector.recordToolExecution(toolName, tenantId, status, durationMs);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void recordActiveRequest(String model, int delta) {
        for (LlmMetricsCollector collector : collectors) {
            try {
                collector.recordActiveRequest(model, delta);
            } catch (Throwable ignored) {
            }
        }
    }
}
