package com.enterprise.llm.template;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateTest {

    private final PromptTemplateEngine engine = new PromptTemplateEngine();

    @Test
    void shouldRenderVariablesCorrectly() {
        String template = "You are a {{role}}. Context: {{context}}. Task: {{task}}.";
        Map<String, String> vars = Map.of(
                "role", "Senior Java Architect",
                "context", "Migrating monolithic system to virtual threads",
                "task", "Analyze thread dump and give 3 recommendations"
        );

        String rendered = engine.render(template, vars);

        assertThat(rendered).isEqualTo("You are a Senior Java Architect. Context: Migrating monolithic system to virtual threads. Task: Analyze thread dump and give 3 recommendations.");
    }
}
