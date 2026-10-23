package com.enterprise.llm.exception;

public class LlmInvalidRequestException extends LlmException {

    public LlmInvalidRequestException(String message, int statusCode, String errorCode) {
        super(message, statusCode, errorCode != null ? errorCode : "invalid_request", "invalid_request_error", false);
    }
}
