package com.enterprise.llm.guard.dlp;

import java.util.regex.Pattern;

public record DlpRule(
        String id,
        String name,
        String pattern,
        DlpMaskType maskType,
        String replacement,
        boolean enabled
) {
    public DlpRule {
        if (maskType == null) {
            maskType = DlpMaskType.REPLACE_TAG;
        }
        if (replacement == null || replacement.isBlank()) {
            replacement = "[REDACTED_" + id.toUpperCase() + "]";
        }
    }

    public Pattern compilePattern() {
        return Pattern.compile(pattern);
    }
}
