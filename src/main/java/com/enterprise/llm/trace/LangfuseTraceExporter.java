package com.enterprise.llm.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class LangfuseTraceExporter {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTraceExporter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String exportSpanAsJson(LlmSpan span) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("traceId", span.getContext().traceId());
            payload.put("spanId", span.getContext().spanId());
            payload.put("userId", span.getContext().userId());
            payload.put("name", span.getName());
            payload.put("model", span.getModel());
            payload.put("ttftMs", span.getTtftMs());
            payload.put("totalLatencyMs", span.getTotalLatencyMs());
            payload.put("tokensPerSecond", span.getTokensPerSecond());
            payload.put("promptTokens", span.getPromptTokens());
            payload.put("completionTokens", span.getCompletionTokens());
            payload.put("totalTokens", span.getTotalTokens());

            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize Langfuse trace span", e);
            throw new RuntimeException("Trace export failed: " + e.getMessage(), e);
        }
    }
}
