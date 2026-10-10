package com.enterprise.llm.guard;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class ToolActionGatekeeper {

    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("(?i)rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|--recursive|-[a-zA-Z]*f[a-zA-Z]*r)"),
            Pattern.compile("(?i)drop\\s+table"),
            Pattern.compile("(?i)drop\\s+database"),
            Pattern.compile("(?i)truncate\\s+table"),
            Pattern.compile("(?i)truncate\\s+[a-zA-Z0-9_]+"),
            Pattern.compile("(?i)format\\s+[a-z]:"),
            Pattern.compile("(?i)mkfs(\\.[a-z0-9]+)?\\s+"),
            Pattern.compile("(?i)shutdown\\s+-h"),
            Pattern.compile("(?i)reboot"),
            Pattern.compile("(?i)kill\\s+-9\\s+1\\b")
    );

    private static final Set<String> TENANT_KEYS = Set.of(
            "tenant_id", "tenantid", "tenant", "org_id", "orgid", "account_id", "accountid"
    );

    public void validateAction(ToolAction action, String currentTenantId) {
        if (action == null) {
            return;
        }

        // 1. Dangerous action command check
        checkDangerousContent(action.toolName());
        if (action.arguments() != null) {
            for (Map.Entry<String, Object> entry : action.arguments().entrySet()) {
                String valStr = String.valueOf(entry.getValue());
                checkDangerousContent(valStr);
            }
        }

        // 2. Cross-tenant isolation check
        if (currentTenantId != null && action.arguments() != null) {
            for (Map.Entry<String, Object> entry : action.arguments().entrySet()) {
                String normalizedKey = entry.getKey().toLowerCase().replace("-", "_");
                if (TENANT_KEYS.contains(normalizedKey) && entry.getValue() != null) {
                    String specifiedTenantId = String.valueOf(entry.getValue());
                    if (!currentTenantId.equals(specifiedTenantId)) {
                        throw new CrossTenantViolationException(
                                "Cross-tenant access violation: Tool argument specifies tenant '" + specifiedTenantId +
                                "' but caller is authenticated as '" + currentTenantId + "'"
                        );
                    }
                }
            }
        }
    }

    public boolean isAllowed(ToolAction action, String currentTenantId) {
        try {
            validateAction(action, currentTenantId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void checkDangerousContent(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(text).find()) {
                throw new SecurityViolationException("Dangerous tool execution attempt blocked: " + pattern.pattern());
            }
        }
    }
}
