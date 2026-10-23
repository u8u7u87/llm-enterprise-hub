package com.enterprise.llm.metrics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class LlmMetricsCollectorTest {

    @Test
    void testNoOpMetricsCollectorDoesNotThrow() {
        LlmMetricsCollector noop = NoOpMetricsCollector.INSTANCE;
        noop.recordRequest("gpt-4o", "tenant-1", "success", 120, 50L, 25.5, 100, 200);
        noop.recordToolExecution("calc", "tenant-1", "success", 15);
        noop.recordActiveRequest("gpt-4o", 1);
        noop.recordActiveRequest("gpt-4o", -1);
    }

    @Test
    void testCompositeMetricsCollectorBroadcasts() {
        AtomicInteger reqCount = new AtomicInteger(0);
        AtomicInteger toolCount = new AtomicInteger(0);
        AtomicInteger activeDelta = new AtomicInteger(0);

        LlmMetricsCollector custom = new LlmMetricsCollector() {
            @Override
            public void recordRequest(String model, String tenantId, String status, long durationMs, Long ttftMs, Double tps, int promptTokens, int completionTokens) {
                reqCount.incrementAndGet();
            }

            @Override
            public void recordToolExecution(String toolName, String tenantId, String status, long durationMs) {
                toolCount.incrementAndGet();
            }

            @Override
            public void recordActiveRequest(String model, int delta) {
                activeDelta.addAndGet(delta);
            }
        };

        CompositeMetricsCollector composite = new CompositeMetricsCollector();
        composite.addCollector(custom);

        composite.recordRequest("gpt-4o", "tenant-1", "success", 100, 40L, 30.0, 50, 100);
        composite.recordToolExecution("weather", "tenant-1", "success", 10);
        composite.recordActiveRequest("gpt-4o", 2);

        assertThat(reqCount.get()).isEqualTo(1);
        assertThat(toolCount.get()).isEqualTo(1);
        assertThat(activeDelta.get()).isEqualTo(2);
    }
}
