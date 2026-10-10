package com.enterprise.llm.guard;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class PromptGuardrailService {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions"),
            Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|prior|above)\\s+instructions"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+in\\s+dan\\s+mode"),
            Pattern.compile("(?i)reveal\\s+your\\s+(system\\s+prompt|instructions)"),
            Pattern.compile("(?i)output\\s+(all\\s+)?passwords?"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("(?i)system\\s*:\\s*override")
    );

    public boolean isSafe(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }

        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(prompt).find()) {
                return false;
            }
        }
        return true;
    }
}
