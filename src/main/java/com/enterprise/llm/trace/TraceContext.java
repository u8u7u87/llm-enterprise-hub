package com.enterprise.llm.trace;

import java.util.UUID;

public record TraceContext(
        String traceId,
        String spanId,
        String userId
) {
    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    public static TraceContext createNew(String userId) {
        return new TraceContext(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                userId
        );
    }

    public static TraceContext getCurrent() {
        return CURRENT.get();
    }

    public static void setCurrent(TraceContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
