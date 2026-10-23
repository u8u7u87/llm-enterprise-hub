package com.enterprise.llm.metrics;

/**
 * Default no-op implementation of {@link LlmMetricsCollector}.
 * Ensures zero overhead when metrics are not configured or disabled.
 */
public final class NoOpMetricsCollector implements LlmMetricsCollector {

    public static final NoOpMetricsCollector INSTANCE = new NoOpMetricsCollector();

    private NoOpMetricsCollector() {
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
        // no-op
    }

    @Override
    public void recordToolExecution(String toolName,
                                    String tenantId,
                                    String status,
                                    long durationMs) {
        // no-op
    }

    @Override
    public void recordActiveRequest(String model, int delta) {
        // no-op
    }
}
