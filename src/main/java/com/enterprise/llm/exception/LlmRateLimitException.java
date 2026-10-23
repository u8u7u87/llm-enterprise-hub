package com.enterprise.llm.exception;

public class LlmRateLimitException extends LlmException {

    private final long retryAfterMs;

    public LlmRateLimitException(String message, String errorCode, long retryAfterMs) {
        super(message, 429, errorCode != null ? errorCode : "rate_limit_exceeded", "rate_limit_error", true);
        this.retryAfterMs = retryAfterMs;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
