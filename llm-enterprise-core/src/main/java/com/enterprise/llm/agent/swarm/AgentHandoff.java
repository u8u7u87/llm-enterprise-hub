package com.enterprise.llm.agent.swarm;

import java.util.Collections;
import java.util.Map;

/**
 * Encapsulates an Agent Handoff event where one agent transfers control
 * to another specialized agent.
 */
public record AgentHandoff(
        String targetAgentRole,
        String reason,
        Map<String, Object> contextUpdates
) {
    public AgentHandoff {
        if (targetAgentRole == null || targetAgentRole.isBlank()) {
            throw new IllegalArgumentException("Target agent role cannot be null or blank");
        }
        if (contextUpdates == null) {
            contextUpdates = Collections.emptyMap();
        }
    }

    public AgentHandoff(String targetAgentRole, String reason) {
        this(targetAgentRole, reason, Collections.emptyMap());
    }
}
