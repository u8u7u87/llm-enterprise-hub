package com.enterprise.llm.guard.prompt;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PropertiesPromptRuleProvider implements PromptRuleProvider {

    private final PromptSecurityProperties properties;
    private volatile List<Pattern> compiledPatterns;

    @Autowired
    public PropertiesPromptRuleProvider(PromptSecurityProperties properties) {
        this.properties = properties != null ? properties : new PromptSecurityProperties();
        this.compiledPatterns = compile(this.properties.getPatterns());
    }

    public PropertiesPromptRuleProvider() {
        this(new PromptSecurityProperties());
    }

    private List<Pattern> compile(List<String> patternStrings) {
        if (patternStrings == null || patternStrings.isEmpty()) {
            return Collections.emptyList();
        }
        return patternStrings.stream()
                .map(p -> {
                    try {
                        return Pattern.compile(p);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<Pattern> getActivePatterns() {
        return compiledPatterns;
    }
}
