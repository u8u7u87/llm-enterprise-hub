package com.enterprise.llm.exception;

public class LlmContentFilterException extends LlmException {

    public LlmContentFilterException(String message, String errorCode) {
        super(message, 400, errorCode != null ? errorCode : "content_filter_triggered", "content_filter_error", false);
    }
}
