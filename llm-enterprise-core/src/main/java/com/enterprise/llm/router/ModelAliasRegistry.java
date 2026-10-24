package com.enterprise.llm.router;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for virtual model aliases and multi-tier cascade fallback lists.
 * E.g., virtual alias "fast" resolves to ["qwen2.5:7b", "gpt-4o-mini", "qwen2.5:0.5b"].
 */
public class ModelAliasRegistry {

    private final Map<String, List<String>> aliasMap = new ConcurrentHashMap<>();

    public ModelAliasRegistry() {
        initDefaultAliases();
    }

    private void initDefaultAliases() {
        // High-speed low-latency models
        registerAlias("fast", List.of("gpt-4o-mini", "qwen-turbo", "qwen2.5:0.5b"));
        registerAlias("fast-chat", List.of("gpt-4o-mini", "qwen-turbo", "qwen2.5:0.5b"));

        // Deep reasoning and COT models
        registerAlias("reasoning", List.of("deepseek-reasoner", "deepseek-r1", "gpt-4o"));
        registerAlias("heavy-reasoning", List.of("deepseek-reasoner", "deepseek-r1", "gpt-4o"));

        // Balanced general production tier
        registerAlias("general", List.of("deepseek-chat", "gpt-4o-mini", "qwen-plus"));
        registerAlias("balanced", List.of("deepseek-chat", "gpt-4o-mini", "qwen-plus"));

        // Free / local internal compute tier
        registerAlias("free", List.of("qwen2.5:0.5b", "qwen2.5:7b"));
        registerAlias("local", List.of("qwen2.5:0.5b", "qwen2.5:7b"));
    }

    public void registerAlias(String alias, List<String> candidateModels) {
        if (alias != null && candidateModels != null && !candidateModels.isEmpty()) {
            aliasMap.put(alias.toLowerCase(), List.copyOf(candidateModels));
        }
    }

    public void registerAlias(String alias, String... candidateModels) {
        if (candidateModels != null) {
            registerAlias(alias, List.of(candidateModels));
        }
    }

    public boolean isAlias(String modelOrAlias) {
        if (modelOrAlias == null) {
            return false;
        }
        return aliasMap.containsKey(modelOrAlias.toLowerCase());
    }

    /**
     * Resolves the candidate model list for an alias, or returns a singleton list if not an alias.
     */
    public List<String> resolveCandidates(String modelOrAlias) {
        if (modelOrAlias == null || modelOrAlias.isBlank()) {
            return List.of();
        }
        List<String> candidates = aliasMap.get(modelOrAlias.toLowerCase());
        if (candidates != null && !candidates.isEmpty()) {
            return candidates;
        }
        return List.of(modelOrAlias);
    }

    public Map<String, List<String>> getAllAliases() {
        return Collections.unmodifiableMap(aliasMap);
    }
}
