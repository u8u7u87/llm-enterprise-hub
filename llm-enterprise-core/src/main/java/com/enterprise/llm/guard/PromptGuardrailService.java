package com.enterprise.llm.guard;

import com.enterprise.llm.guard.prompt.PromptRuleProvider;
import com.enterprise.llm.guard.prompt.PropertiesPromptRuleProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

@Service
public class PromptGuardrailService {

    private final PromptRuleProvider ruleProvider;
    private final List<Pattern> dynamicPatterns = new CopyOnWriteArrayList<>();

    @Autowired
    public PromptGuardrailService(PromptRuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider != null ? ruleProvider : new PropertiesPromptRuleProvider();
    }

    public PromptGuardrailService() {
        this(new PropertiesPromptRuleProvider());
    }

    public void addDynamicPattern(String regex) {
        if (regex != null && !regex.isBlank()) {
            dynamicPatterns.add(Pattern.compile(regex));
        }
    }

    public boolean isSafe(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }

        if (!ruleProvider.isEnabled()) {
            return true;
        }

        // 1. Check provider patterns
        List<Pattern> patterns = ruleProvider.getActivePatterns();
        if (patterns != null) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(prompt).find()) {
                    return false;
                }
            }
        }

        // 2. Check dynamic patterns
        for (Pattern pattern : dynamicPatterns) {
            if (pattern.matcher(prompt).find()) {
                return false;
            }
        }

        return true;
    }
}
