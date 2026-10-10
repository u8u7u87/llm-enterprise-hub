package com.enterprise.llm.guard.dlp;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class PropertiesDlpRuleProvider implements DlpRuleProvider {

    private final DlpPolicyProperties properties;

    public PropertiesDlpRuleProvider(DlpPolicyProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<DlpRule> getActiveRules() {
        if (!properties.isEnabled() || properties.getRules() == null) {
            return Collections.emptyList();
        }
        return properties.getRules().stream()
                .filter(DlpRule::enabled)
                .toList();
    }
}
