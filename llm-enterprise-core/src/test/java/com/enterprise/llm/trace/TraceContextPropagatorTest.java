package com.enterprise.llm.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextPropagatorTest {

    @Test
    @DisplayName("Verify W3C traceparent generation produces standard 00-32hex-16hex-01 format")
    void testToW3cTraceparent() {
        TraceContext ctx = new TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", "user-1");
        String traceparent = ctx.toW3cTraceparent();
        assertThat(traceparent).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    @DisplayName("Verify UUID with dashes is normalized to 32 hex in traceparent")
    void testUuidNormalizedTo32Hex() {
        TraceContext ctx = new TraceContext("4bf92f35-77b3-4da6-a3ce-929d0e0e4736", "00f067aa0ba902b7", "user-1");
        String traceparent = ctx.toW3cTraceparent();
        assertThat(traceparent).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    @DisplayName("Verify fromW3cTraceparent correctly parses traceparent header")
    void testFromW3cTraceparent() {
        String header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        TraceContext ctx = TraceContext.fromW3cTraceparent(header, "tenant-a", "user-1", "sess-123");
        
        assertThat(ctx).isNotNull();
        assertThat(ctx.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(ctx.parentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(ctx.spanId()).isNotEmpty();
        assertThat(ctx.tenantId()).isEqualTo("tenant-a");
        assertThat(ctx.sessionId()).isEqualTo("sess-123");
    }

    @Test
    @DisplayName("Verify TraceContextPropagator injects traceparent and X-Request-ID into HttpRequest.Builder")
    void testInjectTraceHeaders() {
        TraceContext ctx = TraceContext.createNew("test-trace-id-1234567890abcdef", "tenant-x", "user-y", "session-z");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:8000/v1/chat/completions"));

        TraceContextPropagator.inject(builder, ctx);
        HttpRequest request = builder.build();

        assertThat(request.headers().firstValue(TraceContextPropagator.TRACEPARENT_HEADER)).isPresent();
        assertThat(request.headers().firstValue(TraceContextPropagator.TRACEPARENT_HEADER).get())
                .startsWith("00-")
                .endsWith("-01");
        assertThat(request.headers().firstValue(TraceContextPropagator.REQUEST_ID_HEADER)).isPresent();
        assertThat(request.headers().firstValue(TraceContextPropagator.REQUEST_ID_HEADER).get())
                .isEqualTo(ctx.traceId());
    }
}
