package com.enterprise.llm.trace;

import org.slf4j.MDC;

import java.util.UUID;

public record TraceContext(
        String traceId,
        String spanId,
        String parentSpanId,
        String tenantId,
        String userId,
        String sessionId
) {
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_TENANT_ID = "tenantId";
    public static final String MDC_SESSION_ID = "sessionId";

    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    public TraceContext(String traceId, String spanId, String userId) {
        this(traceId, spanId, null, userId, userId, null);
    }

    public static TraceContext createNew(String userId) {
        return createNew(null, userId, userId, null);
    }

    public static TraceContext createNew(String traceId, String tenantId, String userId, String sessionId) {
        String effectiveTraceId = (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new TraceContext(effectiveTraceId, spanId, null, tenantId, userId, sessionId);
    }

    public String toW3cTraceparent() {
        String rawTrace = (traceId != null && !traceId.isBlank()) ? traceId.replace("-", "") : UUID.randomUUID().toString().replace("-", "");
        String normalizedTrace = normalizeHex(rawTrace, 32);

        String rawSpan = (spanId != null && !spanId.isBlank()) ? spanId.replace("-", "") : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String normalizedSpan = normalizeHex(rawSpan, 16);

        return String.format("00-%s-%s-01", normalizedTrace, normalizedSpan);
    }

    public static TraceContext fromW3cTraceparent(String traceparent, String tenantId, String userId, String sessionId) {
        if (traceparent == null || traceparent.isBlank()) {
            return createNew(null, tenantId, userId, sessionId);
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length < 4 || !"00".equals(parts[0])) {
            return createNew(traceparent, tenantId, userId, sessionId);
        }
        String traceId = parts[1];
        String parentSpanId = parts[2];
        String newSpanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new TraceContext(traceId, newSpanId, parentSpanId, tenantId, userId, sessionId);
    }

    private static String normalizeHex(String str, int targetLen) {
        if (str.length() == targetLen) {
            return str.toLowerCase();
        } else if (str.length() > targetLen) {
            return str.substring(0, targetLen).toLowerCase();
        } else {
            return "0".repeat(targetLen - str.length()) + str.toLowerCase();
        }
    }

    public static TraceContext getCurrent() {
        return CURRENT.get();
    }

    public static void setCurrent(TraceContext context) {
        CURRENT.set(context);
        if (context != null) {
            context.syncToMdc();
        } else {
            clearMdc();
        }
    }

    public void syncToMdc() {
        if (traceId != null) MDC.put(MDC_TRACE_ID, traceId);
        if (spanId != null) MDC.put(MDC_SPAN_ID, spanId);
        if (tenantId != null) MDC.put(MDC_TENANT_ID, tenantId);
        if (sessionId != null) MDC.put(MDC_SESSION_ID, sessionId);
    }

    public static void clearMdc() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_TENANT_ID);
        MDC.remove(MDC_SESSION_ID);
    }

    public static void clear() {
        CURRENT.remove();
        clearMdc();
    }
}
