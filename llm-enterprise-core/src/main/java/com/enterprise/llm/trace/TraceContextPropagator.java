package com.enterprise.llm.trace;

import java.net.http.HttpRequest;

/**
 * Standard W3C Trace Context and Request Correlation Header Propagator.
 * Injects 'traceparent' and 'X-Request-ID' into upstream HTTP requests to
 * enable end-to-end distributed tracing across LLM inference clusters (vLLM, Triton, etc.).
 */
public final class TraceContextPropagator {

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String BAGGAGE_HEADER = "baggage";

    private TraceContextPropagator() {
    }

    /**
     * Injects W3C traceparent and correlation headers into an outgoing HttpRequest.Builder.
     *
     * @param builder the HTTP request builder
     * @param context the current TraceContext (if null, falls back to TraceContext.getCurrent())
     */
    public static void inject(HttpRequest.Builder builder, TraceContext context) {
        if (builder == null) {
            return;
        }

        TraceContext effective = context != null ? context : TraceContext.getCurrent();
        if (effective == null) {
            return;
        }

        String traceparent = effective.toW3cTraceparent();
        if (traceparent != null && !traceparent.isBlank()) {
            builder.header(TRACEPARENT_HEADER, traceparent);
        }

        if (effective.traceId() != null && !effective.traceId().isBlank()) {
            builder.header(REQUEST_ID_HEADER, effective.traceId());
        }

        StringBuilder baggage = new StringBuilder();
        if (effective.tenantId() != null && !effective.tenantId().isBlank()) {
            baggage.append("tenant=").append(effective.tenantId());
        }
        if (effective.sessionId() != null && !effective.sessionId().isBlank()) {
            if (!baggage.isEmpty()) baggage.append(",");
            baggage.append("session=").append(effective.sessionId());
        }
        if (!baggage.isEmpty()) {
            builder.header(BAGGAGE_HEADER, baggage.toString());
        }
    }
}
