package com.enterprise.llm.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Two-way privacy vault settings, bound from {@code llm.privacy}.
 *
 * <p>When enabled, inbound prompts are pseudonymised ({@code <PHONE_1>}) before they leave the
 * gateway and restored on the way back. Disabled by default: it changes what the upstream model
 * sees, so it must be an explicit decision.
 */
@ConfigurationProperties(prefix = "llm.privacy")
public class LlmPrivacyProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
