package com.enterprise.llm.exception;

public class LlmServerException extends LlmException {

    public LlmServerException(String message, int statusCode, String errorCode) {
        super(message, statusCode, errorCode != null ? errorCode : "server_error", "server_error", true);
    }

    public LlmServerException(String message, Throwable cause, int statusCode) {
        super(message, cause, statusCode, "server_error", "server_error", true);
    }
}
