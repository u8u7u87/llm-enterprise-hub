package com.enterprise.llm.metrics;

/**
 * Core SPI interface for collecting LLM execution, streaming, token and tool metrics.
 * Designed to have zero external framework dependencies (pure Java).
 */
public interface LlmMetricsCollector {

    /**
     * Record an LLM request completion (sync or stream).
     *
     * @param model            the model name, e.g. "gpt-4o"
     * @param tenantId         tenant identifier (nullable)
     * @param status           "success" or "error"
     * @param durationMs       total request duration in milliseconds
     * @param ttftMs           time to first token in milliseconds (null if non-streaming or not captured)
     * @param tps              tokens per second (null if not calculated)
     * @param promptTokens     number of prompt/input tokens
     * @param completionTokens number of completion/output tokens
     */
    void recordRequest(String model,
                       String tenantId,
                       String status,
                       long durationMs,
                       Long ttftMs,
                       Double tps,
                       int promptTokens,
                       int completionTokens);

    /**
     * Record a tool execution event.
     *
     * @param toolName   name of the executed tool
     * @param tenantId   tenant identifier (nullable)
     * @param status     "success" or "error"
     * @param durationMs duration of the tool execution in milliseconds
     */
    void recordToolExecution(String toolName,
                             String tenantId,
                             String status,
                             long durationMs);

    /**
     * Track active in-flight requests.
     *
     * @param model model name
     * @param delta +1 on start, -1 on completion
     */
    void recordActiveRequest(String model, int delta);
}
