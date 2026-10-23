package com.enterprise.llm.guard;

import java.util.Collections;
import java.util.Map;

public record ToolAction(
        String toolName,
        Map<String, Object> arguments
) {
    public ToolAction {
        if (arguments == null) {
            arguments = Collections.emptyMap();
        }
    }
}
