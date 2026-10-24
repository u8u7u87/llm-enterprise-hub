package com.enterprise.llm.template;

import com.enterprise.llm.exception.LlmInvalidRequestException;

/**
 * Thrown when required prompt variables are missing during template rendering.
 */
public class MissingTemplateVariableException extends LlmInvalidRequestException {

    private final String templateId;
    private final String variableName;

    public MissingTemplateVariableException(String templateId, String variableName) {
        super(String.format("Missing required prompt variable: %s for template '%s'", variableName, templateId));
        this.templateId = templateId;
        this.variableName = variableName;
    }

    public String getTemplateId() {
        return templateId;
    }

    public String getVariableName() {
        return variableName;
    }
}
