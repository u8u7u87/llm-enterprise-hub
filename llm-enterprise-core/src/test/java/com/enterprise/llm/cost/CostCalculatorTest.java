package com.enterprise.llm.cost;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostCalculatorTest {

    private ModelPricingRegistry registry;
    private CostCalculator calculator;

    @BeforeEach
    void setUp() {
        registry = new ModelPricingRegistry();
        calculator = new CostCalculator(registry);
    }

    @Test
    @DisplayName("Should accurately calculate DeepSeek-Chat cost")
    void shouldCalculateDeepSeekChatCost() {
        // DeepSeek-Chat: $0.14 / 1M prompt, $0.28 / 1M completion
        // 10,000 prompt tokens = 10,000 * 0.14 / 1,000,000 = $0.0014
        // 2,000 completion tokens = 2,000 * 0.28 / 1,000,000 = $0.00056
        // Total = $0.00196
        double cost = calculator.calculateCostUsd("deepseek-chat", 10_000, 2_000);
        assertThat(cost).isEqualTo(0.001960);
    }

    @Test
    @DisplayName("Should accurately calculate GPT-4o cost")
    void shouldCalculateGpt4oCost() {
        // GPT-4o: $2.50 / 1M prompt, $10.00 / 1M completion
        // 1,000 prompt tokens = $0.0025
        // 500 completion tokens = $0.0050
        // Total = $0.0075
        double cost = calculator.calculateCostUsd("gpt-4o", 1_000, 500);
        assertThat(cost).isEqualTo(0.007500);
    }

    @Test
    @DisplayName("Should match prefix wildcard for GPT-4o-mini")
    void shouldMatchPrefixWildcard() {
        // gpt-4o-mini*: $0.15 / 1M prompt, $0.60 / 1M completion
        double cost = calculator.calculateCostUsd("gpt-4o-mini-2024-07-18", 10_000, 10_000);
        // (10_000 * 0.15 + 10_000 * 0.60) / 1_000_000 = 7.5 / 1000 = $0.0075
        assertThat(cost).isEqualTo(0.007500);
    }

    @Test
    @DisplayName("Self-hosted and private cluster models should cost $0.0")
    void shouldCostZeroForPrivateModels() {
        double cost1 = calculator.calculateCostUsd("qwen2.5:0.5b", 50_000, 20_000);
        double cost2 = calculator.calculateCostUsd("vllm/qwen2.5:72b", 50_000, 20_000);
        double cost3 = calculator.calculateCostUsd("unknown-custom-model", 50_000, 20_000);

        assertThat(cost1).isZero();
        assertThat(cost2).isZero();
        assertThat(cost3).isZero();
    }

    @Test
    @DisplayName("Custom pricing registration should take precedence")
    void shouldPrioritizeCustomPricing() {
        registry.registerPricing(new ModelPricing("custom-high-cost", 100.0, 200.0));
        double cost = calculator.calculateCostUsd("custom-high-cost", 1_000_000, 1_000_000);
        assertThat(cost).isEqualTo(300.0);
    }
}
