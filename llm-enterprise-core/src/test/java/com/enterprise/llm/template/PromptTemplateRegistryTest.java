package com.enterprise.llm.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateRegistryTest {

    private PromptTemplateRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PromptTemplateRegistry();
    }

    @Test
    @DisplayName("Should register, version, and render prompt template with variable validation")
    void shouldRegisterAndRenderVersionedTemplate() {
        PromptTemplate t1 = new PromptTemplate(
                "customer_support",
                "v1",
                "You are an agent for {{company}}. Help {{customer}} with: {{issue}}",
                Set.of("company", "customer", "issue"),
                "Initial customer support prompt"
        );

        registry.registerTemplate(t1, true);

        Map<String, String> vars = Map.of(
                "company", "Acme Corp",
                "customer", "Alice",
                "issue", "Billing refund"
        );

        String rendered = registry.render("customer_support", "v1", null, vars);
        assertThat(rendered).isEqualTo("You are an agent for Acme Corp. Help Alice with: Billing refund");

        // Rendering with "latest" or null version should resolve v1
        String renderedLatest = registry.render("customer_support", null, null, vars);
        assertThat(renderedLatest).isEqualTo("You are an agent for Acme Corp. Help Alice with: Billing refund");
    }

    @Test
    @DisplayName("Should throw exception when required template variables are missing")
    void shouldThrowExceptionWhenRequiredVariableMissing() {
        PromptTemplate t = new PromptTemplate(
                "invoice_summary",
                "v1",
                "Summarize invoice {{invoice_id}} for amount {{amount}}",
                Set.of("invoice_id", "amount"),
                "Invoice prompt"
        );
        registry.registerTemplate(t, true);

        // Missing "amount"
        Map<String, String> partialVars = Map.of("invoice_id", "INV-2026");

        assertThatThrownBy(() -> registry.render("invoice_summary", "v1", null, partialVars))
                .isInstanceOf(MissingTemplateVariableException.class)
                .hasMessageContaining("Missing required prompt variable: amount");
    }

    @Test
    @DisplayName("Should prioritize tenant-level template override over global versions")
    void shouldPrioritizeTenantLevelTemplateOverride() {
        PromptTemplate globalV1 = new PromptTemplate(
                "greeting",
                "v1",
                "Hello {{user}}, welcome to our platform!",
                Set.of("user"),
                "Global greeting"
        );
        registry.registerTemplate(globalV1, true);

        PromptTemplate vipTenantGreeting = new PromptTemplate(
                "greeting",
                "vip-custom",
                "Dear VIP Member {{user}}, executive concierge is ready to assist you.",
                Set.of("user"),
                "VIP tenant custom greeting"
        );
        registry.registerTenantOverride("tenant-vip", vipTenantGreeting);

        Map<String, String> vars = Map.of("user", "Dr. Bob");

        // Standard tenant gets global v1
        String renderedStandard = registry.render("greeting", null, "tenant-regular", vars);
        assertThat(renderedStandard).isEqualTo("Hello Dr. Bob, welcome to our platform!");

        // VIP tenant gets custom override
        String renderedVip = registry.render("greeting", null, "tenant-vip", vars);
        assertThat(renderedVip).isEqualTo("Dear VIP Member Dr. Bob, executive concierge is ready to assist you.");
    }
}
