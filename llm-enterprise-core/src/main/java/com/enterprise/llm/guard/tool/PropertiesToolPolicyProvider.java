package com.enterprise.llm.guard.tool;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class PropertiesToolPolicyProvider implements ToolPolicyProvider {

    private final ToolPolicyProperties properties;

    public PropertiesToolPolicyProvider(ToolPolicyProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public boolean isTenantIsolationEnabled() {
        return properties.isTenantIsolationEnabled();
    }

    @Override
    public List<String> getTenantIdentityFields() {
        if (properties.getTenantIdentityFields() == null) {
            return Collections.emptyList();
        }
        return properties.getTenantIdentityFields();
    }

    @Override
    public List<Pattern> getDangerPatterns() {
        if (!properties.isEnabled() || properties.getDangerPatterns() == null) {
            return Collections.emptyList();
        }
        return properties.getDangerPatterns().stream()
                .map(Pattern::compile)
                .toList();
    }
}
