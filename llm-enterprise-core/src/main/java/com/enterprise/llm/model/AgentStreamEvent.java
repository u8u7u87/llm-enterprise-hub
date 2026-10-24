package com.enterprise.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Event-Driven Streaming Agent Lifecycle Event.
 * Emitted over Server-Sent Events (SSE) to notify frontends of intermediate
 * agent reasoning steps, tool execution start, and tool completion results.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentStreamEvent(
        String eventType,
        String toolCallId,
        String toolName,
        String arguments,
        String result,
        Long durationMs
) {
    public static final String EVENT_TOOL_CALL_START = "tool_call_start";
    public static final String EVENT_TOOL_CALL_RESULT = "tool_call_result";
    public static final String EVENT_AGENT_THOUGHT = "agent_thought";

    public static AgentStreamEvent toolStart(String toolCallId, String toolName, String arguments) {
        return new AgentStreamEvent(EVENT_TOOL_CALL_START, toolCallId, toolName, arguments, null, null);
    }

    public static AgentStreamEvent toolResult(String toolCallId, String toolName, String result, long durationMs) {
        return new AgentStreamEvent(EVENT_TOOL_CALL_RESULT, toolCallId, toolName, null, result, durationMs);
    }

    public static AgentStreamEvent thought(String content) {
        return new AgentStreamEvent(EVENT_AGENT_THOUGHT, null, null, null, content, null);
    }
}
