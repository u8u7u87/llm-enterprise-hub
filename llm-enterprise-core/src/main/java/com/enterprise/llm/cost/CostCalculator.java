package com.enterprise.llm.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Enterprise Model Cost Calculator computing USD costs per LLM request.
 */
public class CostCalculator {

    private final ModelPricingRegistry registry;

    public CostCalculator(ModelPricingRegistry registry) {
        this.registry = (registry != null) ? registry : new ModelPricingRegistry();
    }

    public CostCalculator() {
        this(new ModelPricingRegistry());
    }

    /**
     * Calculates the total cost in USD for a given model and token usage.
     *
     * @param model            the model name
     * @param promptTokens     number of input/prompt tokens
     * @param completionTokens number of output/completion tokens
     * @return calculated cost in USD (rounded to 6 decimal places, micro-dollars)
     */
    public double calculateCostUsd(String model, int promptTokens, int completionTokens) {
        if (promptTokens <= 0 && completionTokens <= 0) {
            return 0.0;
        }
        ModelPricing pricing = registry.resolvePricing(model);
        double promptCost = (Math.max(0, promptTokens) * pricing.promptPricePerMillionUsd()) / 1_000_000.0;
        double completionCost = (Math.max(0, completionTokens) * pricing.completionPricePerMillionUsd()) / 1_000_000.0;
        double total = promptCost + completionCost;

        return BigDecimal.valueOf(total)
                .setScale(6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public ModelPricingRegistry getRegistry() {
        return registry;
    }
}
