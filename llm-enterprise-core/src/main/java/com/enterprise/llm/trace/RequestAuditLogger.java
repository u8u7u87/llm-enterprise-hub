package com.enterprise.llm.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class RequestAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("LLM_AUDIT");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void logAudit(LlmSpan span, int statusCode) {
        logAudit(span, statusCode, null);
    }

    public static void logAudit(LlmSpan span, int statusCode, String errorMessage) {
        if (span == null) {
            return;
        }
        try {
            Map<String, Object> audit = new HashMap<>();
            audit.put("@timestamp", Instant.now().toString());
            audit.put("type", "LLM_REQUEST_AUDIT");
            TraceContext ctx = span.getContext();
            if (ctx != null) {
                audit.put("traceId", ctx.traceId());
                audit.put("spanId", ctx.spanId());
                audit.put("tenantId", ctx.tenantId());
                audit.put("sessionId", ctx.sessionId());
                audit.put("userId", ctx.userId());
            }
            audit.put("name", span.getName());
            audit.put("model", span.getModel());
            audit.put("stream", span.isStream());
            audit.put("cacheHit", span.isCacheHit());
            audit.put("ttftMs", span.getTtftMs());
            audit.put("totalLatencyMs", span.getTotalLatencyMs());
            audit.put("tps", Math.round(span.getTokensPerSecond() * 100.0) / 100.0);
            audit.put("promptTokens", span.getPromptTokens());
            audit.put("completionTokens", span.getCompletionTokens());
            audit.put("totalTokens", span.getTotalTokens());
            audit.put("toolsInvoked", span.getToolsInvoked());
            audit.put("statusCode", statusCode);
            if (errorMessage != null && !errorMessage.isBlank()) {
                audit.put("error", errorMessage);
            }
            log.info(objectMapper.writeValueAsString(audit));
        } catch (Exception e) {
            log.warn("Failed to serialize request audit log", e);
        }
    }
}
