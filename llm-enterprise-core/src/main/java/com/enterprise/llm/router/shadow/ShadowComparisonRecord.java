package com.enterprise.llm.router.shadow;

/**
 * Audit and comparison record capturing metrics between primary and shadow model execution.
 */
public record ShadowComparisonRecord(
        String traceId,
        String tenantId,
        String sourceModel,
        String shadowModel,
        long primaryLatencyMs,
        long shadowLatencyMs,
        int primaryTokens,
        int shadowTokens,
        String primaryOutputSnippet,
        String shadowOutputSnippet,
        boolean shadowSuccess,
        String shadowErrorMessage
) {
}
