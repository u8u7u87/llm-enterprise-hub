package com.enterprise.llm.guard.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "security.guardrail.prompt")
public class PromptSecurityProperties {

    private boolean enabled = true;

    private List<String> patterns = new ArrayList<>(List.of(
            "(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions",
            "(?i)disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions",
            "(?i)you\\s+are\\s+now\\s+in\\s+dan\\s+mode",
            "(?i)reveal\\s+your\\s+(system\\s+prompt|instructions)",
            "(?i)output\\s+(all\\s+)?passwords?",
            "(?i)jailbreak",
            "(?i)system\\s*:\\s*override"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPatterns() {
        return patterns;
    }

    public void setPatterns(List<String> patterns) {
        this.patterns = patterns;
    }
}
