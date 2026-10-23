package com.enterprise.llm.exception;

public class LlmAuthenticationException extends LlmException {

    public LlmAuthenticationException(String message, int statusCode, String errorCode) {
        super(message, statusCode, errorCode != null ? errorCode : "invalid_api_key", "authentication_error", false);
    }

    public LlmAuthenticationException(String message, String errorCode) {
        this(message, 401, errorCode);
    }
}
