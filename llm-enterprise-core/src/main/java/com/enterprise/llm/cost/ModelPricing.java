package com.enterprise.llm.cost;

import java.util.Objects;

/**
 * Model pricing definition specifying pricing per 1 million tokens in USD.
 */
public record ModelPricing(
        String modelPattern,
        double promptPricePerMillionUsd,
        double completionPricePerMillionUsd
) {

    public ModelPricing {
        Objects.requireNonNull(modelPattern, "modelPattern must not be null");
        if (promptPricePerMillionUsd < 0 || completionPricePerMillionUsd < 0) {
            throw new IllegalArgumentException("Token prices cannot be negative");
        }
    }

    /**
     * Checks if this pricing rule matches the requested model name.
     */
    public boolean matches(String model) {
        if (model == null) {
            return false;
        }
        if (modelPattern.equalsIgnoreCase(model)) {
            return true;
        }
        if (modelPattern.endsWith("*")) {
            String prefix = modelPattern.substring(0, modelPattern.length() - 1);
            return model.toLowerCase().startsWith(prefix.toLowerCase());
        }
        return false;
    }
}
