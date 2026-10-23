package com.enterprise.llm.guard.dlp;

import java.util.List;

public interface DlpRuleProvider {
    List<DlpRule> getActiveRules();
}
