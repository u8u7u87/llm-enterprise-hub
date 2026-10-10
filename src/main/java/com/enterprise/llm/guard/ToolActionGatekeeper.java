package com.enterprise.llm.guard;

import com.enterprise.llm.guard.tool.PropertiesToolPolicyProvider;
import com.enterprise.llm.guard.tool.ToolPolicyProperties;
import com.enterprise.llm.guard.tool.ToolPolicyProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

@Service
public class ToolActionGatekeeper {

    private final ToolPolicyProvider policyProvider;
    private final List<Pattern> dynamicDangerPatterns = new CopyOnWriteArrayList<>();

    @Autowired
    public ToolActionGatekeeper(ToolPolicyProvider policyProvider) {
        this.policyProvider = policyProvider;
    }

    public ToolActionGatekeeper() {
        this(new PropertiesToolPolicyProvider(new ToolPolicyProperties()));
    }

    public void addDangerPattern(String regex) {
        if (regex != null && !regex.isBlank()) {
            dynamicDangerPatterns.add(Pattern.compile(regex));
        }
    }

    public void validateAction(ToolAction action, String currentTenantId) {
        if (action == null || !policyProvider.isEnabled()) {
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
        if (policyProvider.isTenantIsolationEnabled() && currentTenantId != null && action.arguments() != null) {
            List<String> identityFields = policyProvider.getTenantIdentityFields();
            for (Map.Entry<String, Object> entry : action.arguments().entrySet()) {
                String normalizedKey = entry.getKey().toLowerCase().replace("-", "_");
                if (identityFields.contains(normalizedKey) && entry.getValue() != null) {
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
        for (Pattern pattern : policyProvider.getDangerPatterns()) {
            if (pattern.matcher(text).find()) {
                throw new SecurityViolationException("Dangerous tool execution attempt blocked by policy rule: " + pattern.pattern());
            }
        }
        for (Pattern pattern : dynamicDangerPatterns) {
            if (pattern.matcher(text).find()) {
                throw new SecurityViolationException("Dangerous tool execution attempt blocked by dynamic rule: " + pattern.pattern());
            }
        }
    }
}
