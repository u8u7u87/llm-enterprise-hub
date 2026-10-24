package com.enterprise.llm.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enterprise centralized prompt template registry with versioning,
 * parameter validation, and tenant-level prompt customization.
 */
@Component
public class PromptTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRegistry.class);

    // templateId -> (version -> PromptTemplate)
    private final Map<String, Map<String, PromptTemplate>> templates = new ConcurrentHashMap<>();

    // templateId -> latestVersion
    private final Map<String, String> latestVersions = new ConcurrentHashMap<>();

    // "tenantId:templateId" -> PromptTemplate override
    private final Map<String, PromptTemplate> tenantOverrides = new ConcurrentHashMap<>();

    private final PromptTemplateEngine engine = new PromptTemplateEngine();

    /**
     * Registers a prompt template version.
     */
    public void registerTemplate(PromptTemplate template, boolean isLatest) {
        if (template == null) {
            return;
        }

        templates.computeIfAbsent(template.templateId(), k -> new ConcurrentHashMap<>())
                .put(template.version(), template);

        if (isLatest || !latestVersions.containsKey(template.templateId())) {
            latestVersions.put(template.templateId(), template.version());
        }

        log.info("Registered prompt template: id={}, version={}, isLatest={}",
                template.templateId(), template.version(), isLatest);
    }

    /**
     * Registers a tenant-specific template override.
     */
    public void registerTenantOverride(String tenantId, PromptTemplate template) {
        if (tenantId != null && template != null) {
            tenantOverrides.put(tenantId + ":" + template.templateId(), template);
            log.info("Registered tenant prompt override: tenant={}, templateId={}", tenantId, template.templateId());
        }
    }

    public void removeTenantOverride(String tenantId, String templateId) {
        if (tenantId != null && templateId != null) {
            tenantOverrides.remove(tenantId + ":" + templateId);
        }
    }

    /**
     * Retrieves a template by checking tenant override -> specific version -> latest version.
     */
    public Optional<PromptTemplate> getTemplate(String templateId, String version, String tenantId) {
        if (templateId == null) {
            return Optional.empty();
        }

        // 1. Check tenant override
        if (tenantId != null) {
            PromptTemplate override = tenantOverrides.get(tenantId + ":" + templateId);
            if (override != null) {
                return Optional.of(override);
            }
        }

        // 2. Check specific or latest version
        Map<String, PromptTemplate> versionMap = templates.get(templateId);
        if (versionMap == null || versionMap.isEmpty()) {
            return Optional.empty();
        }

        String targetVersion = (version != null && !version.isBlank() && !"latest".equalsIgnoreCase(version))
                ? version
                : latestVersions.get(templateId);

        if (targetVersion != null) {
            PromptTemplate t = versionMap.get(targetVersion);
            if (t != null) {
                return Optional.of(t);
            }
        }

        // Fallback to any available version
        return versionMap.values().stream().findFirst();
    }

    /**
     * Renders a registered template with variable validation.
     */
    public String render(String templateId, String version, String tenantId, Map<String, String> variables) {
        PromptTemplate template = getTemplate(templateId, version, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt template not found: " + templateId));

        // Validate required variables
        if (template.requiredVariables() != null) {
            for (String required : template.requiredVariables()) {
                if (variables == null || !variables.containsKey(required) || variables.get(required) == null) {
                    throw new MissingTemplateVariableException(templateId, required);
                }
            }
        }

        return engine.render(template.templateContent(), variables);
    }
}
