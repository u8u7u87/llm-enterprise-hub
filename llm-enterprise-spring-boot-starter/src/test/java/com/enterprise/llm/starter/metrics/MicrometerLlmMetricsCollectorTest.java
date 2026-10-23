package com.enterprise.llm.starter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerLlmMetricsCollectorTest {

    @Test
    @DisplayName("Verify recordRequest correctly registers and increments Micrometer meters")
    void testRecordRequest() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLlmMetricsCollector collector = new MicrometerLlmMetricsCollector(registry);

        collector.recordRequest("gpt-4o", "tenant-test", "success", 1200, 250L, 35.5, 120, 300);

        // Check requests total counter
        Counter reqCounter = registry.find(MicrometerLlmMetricsCollector.METRIC_REQUESTS_TOTAL)
                .tag("model", "gpt-4o")
                .tag("tenant", "tenant-test")
                .tag("status", "success")
                .counter();
        assertThat(reqCounter).isNotNull();
        assertThat(reqCounter.count()).isEqualTo(1.0);

        // Check duration timer
        Timer durationTimer = registry.find(MicrometerLlmMetricsCollector.METRIC_REQUEST_DURATION)
                .tag("model", "gpt-4o")
                .timer();
        assertThat(durationTimer).isNotNull();
        assertThat(durationTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1200.0);

        // Check TTFT timer
        Timer ttftTimer = registry.find(MicrometerLlmMetricsCollector.METRIC_TTFT)
                .tag("model", "gpt-4o")
                .timer();
        assertThat(ttftTimer).isNotNull();
        assertThat(ttftTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250.0);

        // Check TPS summary
        DistributionSummary tpsSummary = registry.find(MicrometerLlmMetricsCollector.METRIC_TPS)
                .tag("model", "gpt-4o")
                .summary();
        assertThat(tpsSummary).isNotNull();
        assertThat(tpsSummary.totalAmount()).isEqualTo(35.5);

        // Check prompt tokens counter
        Counter promptTokens = registry.find(MicrometerLlmMetricsCollector.METRIC_TOKENS_TOTAL)
                .tag("type", "prompt")
                .counter();
        assertThat(promptTokens).isNotNull();
        assertThat(promptTokens.count()).isEqualTo(120.0);

        // Check completion tokens counter
        Counter compTokens = registry.find(MicrometerLlmMetricsCollector.METRIC_TOKENS_TOTAL)
                .tag("type", "completion")
                .counter();
        assertThat(compTokens).isNotNull();
        assertThat(compTokens.count()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("Verify recordToolExecution and active requests gauge")
    void testToolAndActiveRequests() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerLlmMetricsCollector collector = new MicrometerLlmMetricsCollector(registry);

        // Test tool recording
        collector.recordToolExecution("search_tool", "tenant-test", "success", 85);
        Timer toolTimer = registry.find(MicrometerLlmMetricsCollector.METRIC_TOOL_DURATION)
                .tag("tool", "search_tool")
                .timer();
        assertThat(toolTimer).isNotNull();
        assertThat(toolTimer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(85.0);

        // Test active requests gauge
        collector.recordActiveRequest("deepseek-chat", 1);
        Gauge gauge = registry.find(MicrometerLlmMetricsCollector.METRIC_ACTIVE_REQUESTS)
                .tag("model", "deepseek-chat")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);

        collector.recordActiveRequest("deepseek-chat", 2);
        assertThat(gauge.value()).isEqualTo(3.0);

        collector.recordActiveRequest("deepseek-chat", -3);
        assertThat(gauge.value()).isEqualTo(0.0);
    }
}
