package com.enterprise.llm.exception;

public class LlmContextLengthExceededException extends LlmException {

    public LlmContextLengthExceededException(String message, String errorCode) {
        super(message, 400, errorCode != null ? errorCode : "context_length_exceeded", "invalid_request_error", false);
    }
}
