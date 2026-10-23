package com.enterprise.llm.agent.swarm;

import com.enterprise.llm.agent.ToolDefinition;
import com.enterprise.llm.agent.ToolHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declares an autonomous Agent Role within a Multi-Agent Swarm.
 */
public class AgentRole {

    private final String name;
    private final String instructions;
    private final List<ToolDefinition> tools;
    private final List<String> handoffTargets;
    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();

    public AgentRole(String name, String instructions, List<ToolDefinition> tools, List<String> handoffTargets) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AgentRole name cannot be null or blank");
        }
        this.name = name;
        this.instructions = instructions != null ? instructions : "";
        this.tools = tools != null ? new ArrayList<>(tools) : new ArrayList<>();
        this.handoffTargets = handoffTargets != null ? new ArrayList<>(handoffTargets) : new ArrayList<>();
    }

    public AgentRole(String name, String instructions) {
        this(name, instructions, Collections.emptyList(), Collections.emptyList());
    }

    public String getName() {
        return name;
    }

    public String getInstructions() {
        return instructions;
    }

    public List<ToolDefinition> getTools() {
        return Collections.unmodifiableList(tools);
    }

    public List<String> getHandoffTargets() {
        return Collections.unmodifiableList(handoffTargets);
    }

    public AgentRole registerHandler(String toolName, ToolHandler handler) {
        if (toolName != null && handler != null) {
            handlers.put(toolName, handler);
        }
        return this;
    }

    public ToolHandler getHandler(String toolName) {
        return handlers.get(toolName);
    }

    public Map<String, ToolHandler> getHandlers() {
        return Collections.unmodifiableMap(handlers);
    }
}
