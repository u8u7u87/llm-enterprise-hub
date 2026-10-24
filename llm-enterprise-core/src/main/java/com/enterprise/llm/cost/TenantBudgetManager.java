package com.enterprise.llm.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Thread-safe financial budget manager for enterprise tenants.
 * Supports multi-tier spend governance (OK -> Soft Warning -> Hard Cutoff),
 * operational alert listeners, and zero-downtime billing cycle rollovers.
 */
public class TenantBudgetManager {

    private static final Logger log = LoggerFactory.getLogger(TenantBudgetManager.class);

    public enum BudgetStatus {
        OK,
        WARNING,
        EXCEEDED
    }

    @FunctionalInterface
    public interface BudgetWarningListener {
        void onWarning(String tenantId, double currentSpend, double budgetLimit, double spendRatio);
    }

    private final Map<String, Double> tenantLimits = new ConcurrentHashMap<>();
    private final Map<String, DoubleAdder> tenantSpend = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> warningAlertTriggered = new ConcurrentHashMap<>();

    private volatile double warningRatio = 0.80; // 80% soft warning threshold
    private volatile BudgetWarningListener warningListener;

    public void setWarningRatio(double warningRatio) {
        if (warningRatio > 0.0 && warningRatio < 1.0) {
            this.warningRatio = warningRatio;
        }
    }

    public double getWarningRatio() {
        return warningRatio;
    }

    public void setWarningListener(BudgetWarningListener warningListener) {
        this.warningListener = warningListener;
    }

    /**
     * Set maximum allowed spend in USD for a tenant.
     */
    public void setBudgetLimit(String tenantId, double maxSpendUsd) {
        if (tenantId != null && maxSpendUsd >= 0) {
            tenantLimits.put(tenantId, maxSpendUsd);
            warningAlertTriggered.put(tenantId, new AtomicBoolean(false));
        }
    }

    /**
     * Remove budget limit for a tenant (unlimited).
     */
    public void removeBudgetLimit(String tenantId) {
        if (tenantId != null) {
            tenantLimits.remove(tenantId);
            warningAlertTriggered.remove(tenantId);
        }
    }

    /**
     * Inspects current budget health status for a tenant.
     */
    public BudgetStatus getBudgetStatus(String tenantId) {
        if (tenantId == null) {
            return BudgetStatus.OK;
        }
        Double limit = tenantLimits.get(tenantId);
        if (limit == null || limit <= 0) {
            return BudgetStatus.OK;
        }

        double current = getSpentAmount(tenantId);
        if (current >= limit) {
            return BudgetStatus.EXCEEDED;
        } else if (current >= limit * warningRatio) {
            return BudgetStatus.WARNING;
        }
        return BudgetStatus.OK;
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
     * Records new spend for a tenant, triggers soft-warning callback if threshold exceeded,
     * and returns the new cumulative spent amount.
     */
    public double recordSpend(String tenantId, double costUsd) {
        if (tenantId == null || costUsd <= 0) {
            return 0.0;
        }
        DoubleAdder adder = tenantSpend.computeIfAbsent(tenantId, k -> new DoubleAdder());
        adder.add(costUsd);
        double totalSpend = getSpentAmount(tenantId);

        Double limit = tenantLimits.get(tenantId);
        if (limit != null && limit > 0) {
            double ratio = totalSpend / limit;
            if (ratio >= warningRatio && ratio < 1.0) {
                AtomicBoolean triggered = warningAlertTriggered.computeIfAbsent(tenantId, k -> new AtomicBoolean(false));
                if (triggered.compareAndSet(false, true)) {
                    log.warn("Tenant {} reached {}% budget warning threshold! Current=${}, Limit=${}",
                            tenantId, Math.round(ratio * 100), totalSpend, limit);
                    if (warningListener != null) {
                        try {
                            warningListener.onWarning(tenantId, totalSpend, limit, ratio);
                        } catch (Exception e) {
                            log.error("Error in budget warning listener: {}", e.getMessage(), e);
                        }
                    }
                }
            }
        }
        return totalSpend;
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
            AtomicBoolean triggered = warningAlertTriggered.get(tenantId);
            if (triggered != null) {
                triggered.set(false);
            }
        }
    }
}
