package com.enterprise.llm.exception;

public class LlmRateLimitException extends LlmException {

    private final long retryAfterMs;

    public LlmRateLimitException(String message, String errorCode, long retryAfterMs) {
        super(message, 429, errorCode != null ? errorCode : "rate_limit_exceeded", "rate_limit_error", true);
        this.retryAfterMs = retryAfterMs;
    }

    public LlmRateLimitException(long retryAfterSeconds, String message) {
        this(message, "rate_limit_exceeded", retryAfterSeconds * 1000L);
    }

    public LlmRateLimitException(String message) {
        this(message, "rate_limit_exceeded", 60000L);
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
