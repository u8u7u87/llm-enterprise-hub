package com.enterprise.llm.exception;

public abstract class LlmException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;
    private final String errorType;
    private final boolean retryable;

    public LlmException(String message, int statusCode, String errorCode, String errorType, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode != null ? errorCode : "llm_error";
        this.errorType = errorType != null ? errorType : "api_error";
        this.retryable = retryable;
    }

    public LlmException(String message, Throwable cause, int statusCode, String errorCode, String errorType, boolean retryable) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode != null ? errorCode : "llm_error";
        this.errorType = errorType != null ? errorType : "api_error";
        this.retryable = retryable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorType() {
        return errorType;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
