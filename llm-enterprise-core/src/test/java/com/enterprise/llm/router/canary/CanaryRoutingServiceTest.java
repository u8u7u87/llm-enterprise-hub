package com.enterprise.llm.router.canary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanaryRoutingServiceTest {

    private CanaryRoutingService canaryService;

    @BeforeEach
    void setUp() {
        canaryService = new CanaryRoutingService();
    }

    @Test
    @DisplayName("Should return requested model when no canary rule exists")
    void shouldReturnDefaultWhenNoRule() {
        String resolved = canaryService.resolveModel("gpt-4o", "tenant-1", "sess-1");
        assertThat(resolved).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("Should route 100% to canary model when weight is 100")
    void shouldRouteAllToCanaryWhenWeight100() {
        canaryService.setRule(new CanaryRule("gpt-4o", "deepseek-r1", 100, false, false));
        String resolved = canaryService.resolveModel("gpt-4o", "tenant-1", null);
        assertThat(resolved).isEqualTo("deepseek-r1");
    }

    @Test
    @DisplayName("Should route 0% to canary model when weight is 0")
    void shouldRouteNoneToCanaryWhenWeight0() {
        canaryService.setRule(new CanaryRule("gpt-4o", "deepseek-r1", 0, false, false));
        String resolved = canaryService.resolveModel("gpt-4o", "tenant-1", null);
        assertThat(resolved).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("Should provide deterministic sticky routing by tenantId")
    void shouldProvideStickyRoutingByTenantId() {
        canaryService.setRule(new CanaryRule("gpt-4o", "qwen2.5:72b", 30, true, false));

        // Repeat 20 times: the exact same tenant must always resolve to the exact same model
        String modelTenantA = canaryService.resolveModel("gpt-4o", "tenant-stable-123", null);
        for (int i = 0; i < 20; i++) {
            assertThat(canaryService.resolveModel("gpt-4o", "tenant-stable-123", null))
                    .isEqualTo(modelTenantA);
        }

        String modelTenantB = canaryService.resolveModel("gpt-4o", "tenant-other-999", null);
        for (int i = 0; i < 20; i++) {
            assertThat(canaryService.resolveModel("gpt-4o", "tenant-other-999", null))
                    .isEqualTo(modelTenantB);
        }
    }

    @Test
    @DisplayName("Should prioritize tenant-level canary override when configured")
    void shouldPrioritizeTenantLevelOverride() {
        // Global rule: 10% canary
        canaryService.setRule(new CanaryRule("gpt-4o", "qwen2.5:72b", 10, false, false));

        // Beta tenant gets 100% canary override
        canaryService.setTenantOverride("beta-tenant", "gpt-4o", "qwen2.5:72b");

        String resolvedBeta = canaryService.resolveModel("gpt-4o", "beta-tenant", null);
        assertThat(resolvedBeta).isEqualTo("qwen2.5:72b");

        canaryService.removeTenantOverride("beta-tenant", "gpt-4o");
    }
}
