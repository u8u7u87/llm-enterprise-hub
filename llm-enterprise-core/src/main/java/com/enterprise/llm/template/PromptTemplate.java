package com.enterprise.llm.template;

import java.util.Set;

/**
 * Enterprise versioned prompt template definition.
 *
 * @param templateId        unique identifier for the template (e.g. "order_refund_inquiry")
 * @param version           version identifier (e.g. "v1", "v2", "1.0.0")
 * @param templateContent   text content containing {{variable}} placeholders
 * @param requiredVariables required parameter names that must be provided
 * @param description       human-readable purpose or change note
 */
public record PromptTemplate(
        String templateId,
        String version,
        String templateContent,
        Set<String> requiredVariables,
        String description
) {
    public PromptTemplate {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must not be null or blank");
        }
        if (templateContent == null) {
            templateContent = "";
        }
        if (requiredVariables == null) {
            requiredVariables = Set.of();
        }
    }
}
