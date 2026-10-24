package com.enterprise.llm.cost;

import com.enterprise.llm.exception.LlmException;

/**
 * Exception thrown when a tenant has exceeded their allocated financial budget.
 */
public class TenantBudgetExceededException extends LlmException {

    private final String tenantId;
    private final double currentSpendUsd;
    private final double budgetLimitUsd;

    public TenantBudgetExceededException(String tenantId, double currentSpendUsd, double budgetLimitUsd) {
        super(String.format("Tenant '%s' has exceeded financial budget limit: current spend $%.4f exceeds limit $%.4f",
                tenantId, currentSpendUsd, budgetLimitUsd), 403, "tenant_budget_exceeded", "budget_error", false);
        this.tenantId = tenantId;
        this.currentSpendUsd = currentSpendUsd;
        this.budgetLimitUsd = budgetLimitUsd;
    }

    public String getTenantId() {
        return tenantId;
    }

    public double getCurrentSpendUsd() {
        return currentSpendUsd;
    }

    public double getBudgetLimitUsd() {
        return budgetLimitUsd;
    }
}
