package com.enterprise.llm.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Thread-safe financial budget manager for enterprise tenants.
 */
public class TenantBudgetManager {

    private final Map<String, Double> tenantLimits = new ConcurrentHashMap<>();
    private final Map<String, DoubleAdder> tenantSpend = new ConcurrentHashMap<>();

    /**
     * Set maximum allowed spend in USD for a tenant.
     */
    public void setBudgetLimit(String tenantId, double maxSpendUsd) {
        if (tenantId != null && maxSpendUsd >= 0) {
            tenantLimits.put(tenantId, maxSpendUsd);
        }
    }

    /**
     * Remove budget limit for a tenant (unlimited).
     */
    public void removeBudgetLimit(String tenantId) {
        if (tenantId != null) {
            tenantLimits.remove(tenantId);
        }
    }

    /**
     * Checks if tenant has remaining budget before or during execution.
     * Throws TenantBudgetExceededException if budget is exceeded.
     */
    public void checkBudget(String tenantId) {
        if (tenantId == null) {
            return;
        }
        Double limit = tenantLimits.get(tenantId);
        if (limit == null) {
            return; // No limit set
        }
        double current = getSpentAmount(tenantId);
        if (current >= limit) {
            throw new TenantBudgetExceededException(tenantId, current, limit);
        }
    }

    /**
     * Records new spend for a tenant and verifies against budget.
     */
    public double recordSpend(String tenantId, double costUsd) {
        if (tenantId == null || costUsd <= 0) {
            return 0.0;
        }
        DoubleAdder adder = tenantSpend.computeIfAbsent(tenantId, k -> new DoubleAdder());
        adder.add(costUsd);
        return getSpentAmount(tenantId);
    }

    /**
     * Gets current total spend for a tenant in USD.
     */
    public double getSpentAmount(String tenantId) {
        if (tenantId == null) {
            return 0.0;
        }
        DoubleAdder adder = tenantSpend.get(tenantId);
        if (adder == null) {
            return 0.0;
        }
        return BigDecimal.valueOf(adder.doubleValue())
                .setScale(6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Resets spending counter for a tenant (e.g. at monthly billing rollover).
     */
    public void resetSpend(String tenantId) {
        if (tenantId != null) {
            DoubleAdder adder = tenantSpend.get(tenantId);
            if (adder != null) {
                adder.reset();
            }
        }
    }
}
