package com.enterprise.llm.cost;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantBudgetManagerTest {

    private TenantBudgetManager budgetManager;

    @BeforeEach
    void setUp() {
        budgetManager = new TenantBudgetManager();
    }

    @Test
    @DisplayName("Should track accumulated spend and enforce budget limit")
    void shouldTrackSpendAndEnforceLimit() {
        String tenantId = "tenant-fintech";
        budgetManager.setBudgetLimit(tenantId, 10.0); // $10 budget

        budgetManager.recordSpend(tenantId, 3.50);
        assertThat(budgetManager.getSpentAmount(tenantId)).isEqualTo(3.50);

        budgetManager.checkBudget(tenantId); // should pass

        budgetManager.recordSpend(tenantId, 6.50);
        assertThat(budgetManager.getSpentAmount(tenantId)).isEqualTo(10.0);

        // Budget reached: checking budget throws TenantBudgetExceededException
        assertThatThrownBy(() -> budgetManager.checkBudget(tenantId))
                .isInstanceOf(TenantBudgetExceededException.class)
                .hasMessageContaining("has exceeded financial budget limit");
    }

    @Test
    @DisplayName("Tenant without limit should have unlimited spend")
    void shouldAllowUnlimitedSpendWhenNoLimit() {
        String tenantId = "unlimited-corp";
        budgetManager.recordSpend(tenantId, 5000.0);
        assertThat(budgetManager.getSpentAmount(tenantId)).isEqualTo(5000.0);
        // Should not throw
        budgetManager.checkBudget(tenantId);
    }

    @Test
    @DisplayName("Should reset spend on billing cycle rollover")
    void shouldResetSpend() {
        String tenantId = "cycle-test";
        budgetManager.recordSpend(tenantId, 25.0);
        assertThat(budgetManager.getSpentAmount(tenantId)).isEqualTo(25.0);

        budgetManager.resetSpend(tenantId);
        assertThat(budgetManager.getSpentAmount(tenantId)).isZero();
    }

    @Test
    @DisplayName("Should detect OK, WARNING, and EXCEEDED status tiers and fire warning listener")
    void shouldHandleStatusTiersAndWarningListener() {
        String tenantId = "warning-corp";
        budgetManager.setBudgetLimit(tenantId, 100.0);
        budgetManager.setWarningRatio(0.80);

        final java.util.concurrent.atomic.AtomicBoolean listenerInvoked = new java.util.concurrent.atomic.AtomicBoolean(false);
        budgetManager.setWarningListener((id, spent, limit, ratio) -> {
            if (id.equals(tenantId) && ratio >= 0.8) {
                listenerInvoked.set(true);
            }
        });

        // 1. Initial status is OK
        assertThat(budgetManager.getBudgetStatus(tenantId)).isEqualTo(TenantBudgetManager.BudgetStatus.OK);

        // 2. Spend 50 USD -> 50% -> status OK
        budgetManager.recordSpend(tenantId, 50.0);
        assertThat(budgetManager.getBudgetStatus(tenantId)).isEqualTo(TenantBudgetManager.BudgetStatus.OK);
        assertThat(listenerInvoked.get()).isFalse();

        // 3. Spend 35 USD -> 85% -> status WARNING and listener fires!
        budgetManager.recordSpend(tenantId, 35.0);
        assertThat(budgetManager.getBudgetStatus(tenantId)).isEqualTo(TenantBudgetManager.BudgetStatus.WARNING);
        assertThat(listenerInvoked.get()).isTrue();

        // 4. Spend 20 USD -> 105% -> status EXCEEDED
        budgetManager.recordSpend(tenantId, 20.0);
        assertThat(budgetManager.getBudgetStatus(tenantId)).isEqualTo(TenantBudgetManager.BudgetStatus.EXCEEDED);
    }
}
