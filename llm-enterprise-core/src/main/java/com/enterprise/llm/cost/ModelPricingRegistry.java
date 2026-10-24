package com.enterprise.llm.cost;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry holding model pricing configurations with official rates for major LLM providers.
 */
public class ModelPricingRegistry {

    private final List<ModelPricing> pricings = new CopyOnWriteArrayList<>();
    private final ModelPricing defaultFallback = new ModelPricing("*", 0.0, 0.0);

    public ModelPricingRegistry() {
        initDefaultPricings();
    }

    private void initDefaultPricings() {
        // DeepSeek official rates ($/1M tokens)
        registerPricing(new ModelPricing("deepseek-chat", 0.14, 0.28));
        registerPricing(new ModelPricing("deepseek-reasoner", 0.55, 2.19));
        registerPricing(new ModelPricing("deepseek-r1*", 0.55, 2.19));
        registerPricing(new ModelPricing("deepseek-v3*", 0.14, 0.28));

        // OpenAI official rates ($/1M tokens)
        registerPricing(new ModelPricing("gpt-4o-mini*", 0.15, 0.60));
        registerPricing(new ModelPricing("gpt-4o*", 2.50, 10.00));
        registerPricing(new ModelPricing("gpt-4-turbo*", 10.00, 30.00));
        registerPricing(new ModelPricing("gpt-3.5-turbo*", 0.50, 1.50));

        // Qwen official cloud rates ($/1M tokens approx converted)
        registerPricing(new ModelPricing("qwen-turbo*", 0.05, 0.20));
        registerPricing(new ModelPricing("qwen-plus*", 0.11, 0.28));
        registerPricing(new ModelPricing("qwen-max*", 2.80, 8.40));

        // Self-hosted / Open source models default to 0.0 cost
        registerPricing(new ModelPricing("qwen2.5*", 0.0, 0.0));
        registerPricing(new ModelPricing("llama*", 0.0, 0.0));
        registerPricing(new ModelPricing("ollama/*", 0.0, 0.0));
        registerPricing(new ModelPricing("vllm/*", 0.0, 0.0));
        registerPricing(new ModelPricing("triton/*", 0.0, 0.0));
    }

    public void registerPricing(ModelPricing pricing) {
        if (pricing != null) {
            // New or custom pricing is inserted at front to take precedence
            pricings.add(0, pricing);
        }
    }

    public ModelPricing resolvePricing(String model) {
        if (model == null || model.isBlank()) {
            return defaultFallback;
        }
        ModelPricing bestMatch = null;
        for (ModelPricing pricing : pricings) {
            if (pricing.matches(model)) {
                // Exact match takes immediate highest priority
                if (pricing.modelPattern().equalsIgnoreCase(model)) {
                    return pricing;
                }
                // Otherwise pick the longest/most specific pattern
                if (bestMatch == null || pricing.modelPattern().length() > bestMatch.modelPattern().length()) {
                    bestMatch = pricing;
                }
            }
        }
        return bestMatch != null ? bestMatch : defaultFallback;
    }

    public List<ModelPricing> getAllPricings() {
        return List.copyOf(pricings);
    }
}
