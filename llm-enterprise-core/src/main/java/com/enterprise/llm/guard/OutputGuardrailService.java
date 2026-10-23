package com.enterprise.llm.guard;

import com.enterprise.llm.guard.dlp.DlpMaskType;
import com.enterprise.llm.guard.dlp.DlpPolicyProperties;
import com.enterprise.llm.guard.dlp.DlpRule;
import com.enterprise.llm.guard.dlp.DlpRuleProvider;
import com.enterprise.llm.guard.dlp.PropertiesDlpRuleProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OutputGuardrailService {

    private final DlpRuleProvider ruleProvider;
    private final List<DlpRule> dynamicRules = new CopyOnWriteArrayList<>();

    @Autowired
    public OutputGuardrailService(DlpRuleProvider ruleProvider) {
        this.ruleProvider = ruleProvider;
    }

    public OutputGuardrailService() {
        this(new PropertiesDlpRuleProvider(new DlpPolicyProperties()));
    }

    public void addDynamicRule(DlpRule rule) {
        if (rule != null) {
            dynamicRules.add(rule);
        }
    }

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        // 1. Apply rules from configured provider
        List<DlpRule> activeRules = ruleProvider.getActiveRules();
        for (DlpRule rule : activeRules) {
            result = applyRule(result, rule);
        }

        // 2. Apply any dynamic rules
        for (DlpRule rule : dynamicRules) {
            if (rule.enabled()) {
                result = applyRule(result, rule);
            }
        }

        return result;
    }

    public boolean containsSensitiveData(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        List<DlpRule> activeRules = ruleProvider.getActiveRules();
        for (DlpRule rule : activeRules) {
            if (rule.compilePattern().matcher(text).find()) {
                return true;
            }
        }
        for (DlpRule rule : dynamicRules) {
            if (rule.enabled() && rule.compilePattern().matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private String applyRule(String input, DlpRule rule) {
        Pattern pattern = rule.compilePattern();
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }

        if (rule.maskType() == DlpMaskType.BLOCK) {
            throw new SecurityViolationException("Sensitive data output blocked by DLP rule: " + rule.name());
        }

        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String matched = matcher.group();
            String replacement = resolveReplacement(matched, rule);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String resolveReplacement(String matched, DlpRule rule) {
        if (rule.maskType() == DlpMaskType.PARTIAL_MASK && matched.length() > 6) {
            int pre = Math.min(3, matched.length() / 4);
            int suf = Math.min(4, matched.length() / 4);
            return matched.substring(0, pre) + "****" + matched.substring(matched.length() - suf);
        }
        return rule.replacement();
    }
}
