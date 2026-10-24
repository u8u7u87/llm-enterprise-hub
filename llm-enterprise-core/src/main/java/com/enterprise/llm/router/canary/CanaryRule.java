package com.enterprise.llm.router.canary;

/**
 * Canary routing rule configuration.
 *
 * @param baseModel       original base model identifier
 * @param canaryModel     candidate canary model identifier
 * @param weightPercent   percentage of traffic routed to canary (0-100)
 * @param stickyByTenant  if true, traffic assignment is deterministic by tenantId hash
 * @param stickyBySession if true, traffic assignment is deterministic by sessionId hash
 */
public record CanaryRule(
        String baseModel,
        String canaryModel,
        int weightPercent,
        boolean stickyByTenant,
        boolean stickyBySession
) {
    public CanaryRule {
        if (weightPercent < 0 || weightPercent > 100) {
            throw new IllegalArgumentException("Weight percent must be between 0 and 100, was: " + weightPercent);
        }
    }
}
