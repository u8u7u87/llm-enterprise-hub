package com.enterprise.llm.guard;

public class CrossTenantViolationException extends RuntimeException {
    public CrossTenantViolationException(String message) {
        super(message);
    }
}
