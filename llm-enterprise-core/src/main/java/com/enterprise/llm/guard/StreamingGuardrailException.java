package com.enterprise.llm.guard;

import com.enterprise.llm.exception.LlmException;

/**
 * Exception thrown when the streaming output guardrail detects a real-time sensitive data leak.
 */
public class StreamingGuardrailException extends LlmException {

    private final String violationType;
    private final String snippet;

    public StreamingGuardrailException(String violationType, String snippet) {
        super(String.format("Streaming output blocked by guardrail: detected sensitive %s violation", violationType),
                400, "sensitive_data_leak_blocked", "guardrail_error", false);
        this.violationType = violationType;
        this.snippet = snippet;
    }

    public String getViolationType() {
        return violationType;
    }

    public String getSnippet() {
        return snippet;
    }
}
