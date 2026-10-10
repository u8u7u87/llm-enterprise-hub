package com.enterprise.llm.guard;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class OutputGuardrailService {

    private record RedactionRule(Pattern pattern, String replacement) {}

    private final List<RedactionRule> rules = List.of(
            // JWT tokens before api keys to avoid partial matching
            new RedactionRule(
                    Pattern.compile("\\bey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\b"),
                    "[REDACTED_JWT]"
            ),
            // OpenAI API Key
            new RedactionRule(
                    Pattern.compile("\\bsk-[a-zA-Z0-9_-]{20,}\\b"),
                    "[REDACTED_API_KEY]"
            ),
            // AWS Access Key ID
            new RedactionRule(
                    Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
                    "[REDACTED_AWS_KEY]"
            ),
            // Chinese 11-digit mobile phones
            new RedactionRule(
                    Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
                    "[REDACTED_PHONE]"
            ),
            // Email addresses
            new RedactionRule(
                    Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
                    "[REDACTED_EMAIL]"
            )
    );

    public String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        for (RedactionRule rule : rules) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    public boolean containsSensitiveData(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (RedactionRule rule : rules) {
            if (rule.pattern().matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
