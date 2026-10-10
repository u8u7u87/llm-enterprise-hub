package com.enterprise.llm.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTracingTest {

    @Test
    void shouldCalculateMetricsAccurately() {
        TraceContext context = new TraceContext("trace-test-123", "span-1", "user-456");
        LlmSpan span = new LlmSpan("llm-chat", context, "deepseek-chat");

        long t0 = System.currentTimeMillis();
        span.start(t0);

        // Record first token chunk at T0 + 120ms
        span.recordFirstToken(t0 + 120);

        // Record finish at T0 + 850ms with 100 completion tokens and 20 prompt tokens
        span.finish(t0 + 850, 20, 100);

        assertThat(span.getTtftMs()).isEqualTo(120);
        assertThat(span.getTotalLatencyMs()).isEqualTo(850);
        assertThat(span.getPromptTokens()).isEqualTo(20);
        assertThat(span.getCompletionTokens()).isEqualTo(100);
        assertThat(span.getTotalTokens()).isEqualTo(120);

        // TPS = 100 tokens / 0.85s ~ 117.65 tokens/sec
        assertThat(span.getTokensPerSecond()).isGreaterThan(115.0).isLessThan(120.0);
    }

    @Test
    void shouldFormatLangfuseExportPayload() {
        TraceContext context = new TraceContext("trace-abc", "span-root", "user-789");
        LlmSpan span = new LlmSpan("generation-1", context, "gpt-4o");
        span.start(1000L);
        span.recordFirstToken(1150L);
        span.finish(2000L, 50, 50);

        TraceCollector collector = new TraceCollector();
        collector.collect(span);

        LangfuseTraceExporter exporter = new LangfuseTraceExporter();
        String jsonPayload = exporter.exportSpanAsJson(span);

        assertThat(jsonPayload).contains("\"traceId\":\"trace-abc\"");
        assertThat(jsonPayload).contains("\"model\":\"gpt-4o\"");
        assertThat(jsonPayload).contains("\"ttftMs\":150");
        assertThat(jsonPayload).contains("\"totalTokens\":100");
    }
}
