package com.enterprise.llm.guard.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "security.tools")
public class ToolPolicyProperties {

    private boolean enabled = true;
    private boolean tenantIsolationEnabled = true;

    private List<String> tenantIdentityFields = new ArrayList<>(List.of(
            "tenant_id", "tenantid", "tenant", "org_id", "orgid", "account_id", "accountid"
    ));

    private List<String> dangerPatterns = new ArrayList<>(List.of(
            "(?i)rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|--recursive|-[a-zA-Z]*f[a-zA-Z]*r)",
            "(?i)drop\\s+table",
            "(?i)drop\\s+database",
            "(?i)truncate\\s+table",
            "(?i)truncate\\s+[a-zA-Z0-9_]+",
            "(?i)format\\s+[a-z]:",
            "(?i)mkfs(\\.[a-z0-9]+)?\\s+",
            "(?i)shutdown\\s+-h",
            "(?i)reboot",
            "(?i)kill\\s+-9\\s+1\\b"
    ));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTenantIsolationEnabled() {
        return tenantIsolationEnabled;
    }

    public void setTenantIsolationEnabled(boolean tenantIsolationEnabled) {
        this.tenantIsolationEnabled = tenantIsolationEnabled;
    }

    public List<String> getTenantIdentityFields() {
        return tenantIdentityFields;
    }

    public void setTenantIdentityFields(List<String> tenantIdentityFields) {
        if (tenantIdentityFields != null && !tenantIdentityFields.isEmpty()) {
            this.tenantIdentityFields = tenantIdentityFields;
        }
    }

    public List<String> getDangerPatterns() {
        return dangerPatterns;
    }

    public void setDangerPatterns(List<String> dangerPatterns) {
        if (dangerPatterns != null && !dangerPatterns.isEmpty()) {
            this.dangerPatterns = dangerPatterns;
        }
    }
}
