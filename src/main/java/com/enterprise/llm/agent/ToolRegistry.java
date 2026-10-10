package com.enterprise.llm.agent;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();

    public record RegisteredTool(
            ToolDefinition definition,
            ToolHandler handler
    ) {
    }

    public void registerTool(ToolDefinition definition, ToolHandler handler) {
        if (definition == null || definition.name() == null) {
            throw new IllegalArgumentException("Tool definition and name must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Tool handler must not be null");
        }
        tools.put(definition.name(), new RegisteredTool(definition, handler));
    }

    public void registerTool(String name, String description, Map<String, Object> parameters, ToolHandler handler) {
        registerTool(new ToolDefinition(name, description, parameters), handler);
    }

    public Optional<RegisteredTool> getTool(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name));
    }

    public boolean hasTool(String name) {
        return name != null && tools.containsKey(name);
    }

    public Collection<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(RegisteredTool::definition)
                .toList();
    }

    public void clear() {
        tools.clear();
    }
}
