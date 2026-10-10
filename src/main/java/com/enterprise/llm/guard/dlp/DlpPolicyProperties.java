package com.enterprise.llm.guard.dlp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "security.dlp")
public class DlpPolicyProperties {

    private boolean enabled = true;
    private List<DlpRule> rules = new ArrayList<>();

    public DlpPolicyProperties() {
        // Initialize industry-standard default rule set
        initDefaultRules();
    }

    private void initDefaultRules() {
        rules.add(new DlpRule(
                "jwt",
                "JWT Token",
                "\\bey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\b",
                DlpMaskType.REPLACE_TAG,
                "[REDACTED_JWT]",
                true
        ));
        rules.add(new DlpRule(
                "openai_key",
                "OpenAI API Key",
                "\\bsk-[a-zA-Z0-9_-]{20,}\\b",
                DlpMaskType.REPLACE_TAG,
                "[REDACTED_API_KEY]",
                true
        ));
        rules.add(new DlpRule(
                "aws_key",
                "AWS Access Key ID",
                "\\bAKIA[0-9A-Z]{16}\\b",
                DlpMaskType.REPLACE_TAG,
                "[REDACTED_AWS_KEY]",
                true
        ));
        rules.add(new DlpRule(
                "phone",
                "Mobile Phone Number",
                "(?<!\\d)1[3-9]\\d{9}(?!\\d)",
                DlpMaskType.REPLACE_TAG,
                "[REDACTED_PHONE]",
                true
        ));
        rules.add(new DlpRule(
                "email",
                "Email Address",
                "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                DlpMaskType.REPLACE_TAG,
                "[REDACTED_EMAIL]",
                true
        ));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<DlpRule> getRules() {
        return rules;
    }

    public void setRules(List<DlpRule> rules) {
        if (rules != null && !rules.isEmpty()) {
            this.rules = rules;
        }
    }
}
