package com.enterprise.llm.auth;

import com.enterprise.llm.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(0)
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String SESSION_HEADER = "X-Session-Id";
    public static final String W3C_TRACEPARENT = "traceparent";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String incomingTraceId = extractTraceId(request);
        String sessionId = request.getHeader(SESSION_HEADER);

        TraceContext context = TraceContext.createNew(incomingTraceId, null, null, sessionId);
        TraceContext.setCurrent(context);

        // Echo trace ID to response header
        response.setHeader(TRACE_HEADER, context.traceId());

        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

    private String extractTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId != null && !traceId.isBlank()) {
            return traceId.trim();
        }
        String traceparent = request.getHeader(W3C_TRACEPARENT);
        if (traceparent != null && !traceparent.isBlank()) {
            // W3C traceparent format: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && !parts[1].isBlank()) {
                return parts[1].trim();
            }
        }
        return null;
    }
}
