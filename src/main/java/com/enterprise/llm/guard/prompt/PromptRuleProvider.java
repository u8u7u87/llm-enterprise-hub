package com.enterprise.llm.guard.prompt;

import java.util.List;
import java.util.regex.Pattern;

public interface PromptRuleProvider {
    boolean isEnabled();
    List<Pattern> getActivePatterns();
}
