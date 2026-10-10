package com.enterprise.llm.guard.tool;

import java.util.List;
import java.util.regex.Pattern;

public interface ToolPolicyProvider {
    boolean isEnabled();
    boolean isTenantIsolationEnabled();
    List<String> getTenantIdentityFields();
    List<Pattern> getDangerPatterns();
}
