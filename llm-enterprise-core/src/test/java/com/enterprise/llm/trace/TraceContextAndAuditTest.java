package com.enterprise.llm.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextAndAuditTest {

    @AfterEach
    void tearDown() {
        TraceContext.clear();
    }

    @Test
    @DisplayName("Verify TraceContext automatically synchronizes with SLF4J MDC and clears properly")
    void testTraceContextMdcSyncAndClear() {
        TraceContext ctx = TraceContext.createNew("trace-999", "tenant-alpha", "user-1", "sess-123");
        TraceContext.setCurrent(ctx);

        assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isEqualTo("trace-999");
        assertThat(MDC.get(TraceContext.MDC_TENANT_ID)).isEqualTo("tenant-alpha");
        assertThat(MDC.get(TraceContext.MDC_SESSION_ID)).isEqualTo("sess-123");
        assertThat(TraceContext.getCurrent()).isNotNull();

        TraceContext.clear();
        assertThat(MDC.get(TraceContext.MDC_TRACE_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_TENANT_ID)).isNull();
        assertThat(MDC.get(TraceContext.MDC_SESSION_ID)).isNull();
        assertThat(TraceContext.getCurrent()).isNull();
    }

    @Test
    @DisplayName("Verify LlmSpan attributes and RequestAuditLogger execution")
    void testLlmSpanAttributesAndAudit() {
        TraceContext ctx = TraceContext.createNew("trace-abc", "tenant-beta", "user-2", "sess-555");
        LlmSpan span = new LlmSpan("chat_sync", ctx, "qwen2.5:0.5b");
        span.start(1000L);
        span.recordFirstToken(1120L);
        span.finish(1500L, 50, 80);
        span.setCacheHit(false);
        span.setStream(false);
        span.addToolInvoked("query_order");

        assertThat(span.getTtftMs()).isEqualTo(120L);
        assertThat(span.getTotalLatencyMs()).isEqualTo(500L);
        assertThat(span.getTotalTokens()).isEqualTo(130);
        assertThat(span.getToolsInvoked()).containsExactly("query_order");
        assertThat(span.isCacheHit()).isFalse();

        // Must run cleanly without throwing exceptions
        RequestAuditLogger.logAudit(span, 200);
        RequestAuditLogger.logAudit(span, 500, "Connection timed out");
    }
}
