package com.enterprise.llm.agent;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();

    public record RegisteredTool(
            ToolDefinition definition,
            ToolHandler handler,
            Set<String> allowedTenants,
            Set<String> scopes
    ) {
        public RegisteredTool(ToolDefinition definition, ToolHandler handler) {
            this(definition, handler, Collections.emptySet(), Collections.emptySet());
        }

        public boolean isAllowedForTenant(String tenantId) {
            if (allowedTenants == null || allowedTenants.isEmpty()) {
                return true;
            }
            return tenantId != null && allowedTenants.contains(tenantId);
        }

        public boolean hasScopes(Collection<String> clientScopes) {
            if (scopes == null || scopes.isEmpty()) {
                return true;
            }
            if (clientScopes == null) {
                return false;
            }
            return clientScopes.containsAll(scopes);
        }
    }

    public void registerTool(ToolDefinition definition, ToolHandler handler, Set<String> allowedTenants, Set<String> scopes) {
        if (definition == null || definition.name() == null) {
            throw new IllegalArgumentException("Tool definition and name must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Tool handler must not be null");
        }
        Set<String> tenants = allowedTenants != null ? Collections.unmodifiableSet(allowedTenants) : Collections.emptySet();
        Set<String> scs = scopes != null ? Collections.unmodifiableSet(scopes) : Collections.emptySet();
        tools.put(definition.name(), new RegisteredTool(definition, handler, tenants, scs));
    }

    public void registerTool(ToolDefinition definition, ToolHandler handler) {
        registerTool(definition, handler, Collections.emptySet(), Collections.emptySet());
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

    public Optional<RegisteredTool> getToolForTenant(String name, String tenantId) {
        return getTool(name).filter(t -> t.isAllowedForTenant(tenantId));
    }

    public boolean hasTool(String name) {
        return name != null && tools.containsKey(name);
    }

    public Collection<ToolDefinition> getAllDefinitions() {
        return tools.values().stream()
                .map(RegisteredTool::definition)
                .toList();
    }

    public Collection<ToolDefinition> getDefinitionsForTenant(String tenantId) {
        return tools.values().stream()
                .filter(t -> t.isAllowedForTenant(tenantId))
                .map(RegisteredTool::definition)
                .toList();
    }

    public Collection<ToolDefinition> getDefinitionsForTenantAndScopes(String tenantId, Collection<String> scopes) {
        return tools.values().stream()
                .filter(t -> t.isAllowedForTenant(tenantId) && t.hasScopes(scopes))
                .map(RegisteredTool::definition)
                .toList();
    }

    public void clear() {
        tools.clear();
    }
}
