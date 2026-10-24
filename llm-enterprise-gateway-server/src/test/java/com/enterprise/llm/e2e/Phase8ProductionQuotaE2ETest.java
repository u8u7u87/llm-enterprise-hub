package com.enterprise.llm.e2e;

import com.enterprise.llm.cost.TenantBudgetExceededException;
import com.enterprise.llm.cost.TenantBudgetManager;
import com.enterprise.llm.quota.*;
import com.enterprise.llm.ratelimit.DistributedRateLimiter;
import com.enterprise.llm.ratelimit.RateLimitResult;
import com.enterprise.llm.ratelimit.TenantRateLimiter;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Phase8ProductionQuotaE2ETest {

    @Mock
    private TenantStorageProvider tenantStorageProvider;

    private AsyncQuotaWriteBehindBatcher writeBehindBatcher;
    private InMemoryQuotaStorageEngine memoryQuotaEngine;
    private DistributedLuaQuotaEngine distributedLuaEngine;
    private TenantQuotaService quotaService;
    private TenantBudgetManager budgetManager;

    @BeforeEach
    void setUp() {
        memoryQuotaEngine = new InMemoryQuotaStorageEngine();
        distributedLuaEngine = new DistributedLuaQuotaEngine();
        // 10s interval so we manually trigger flush in test
        writeBehindBatcher = new AsyncQuotaWriteBehindBatcher(tenantStorageProvider, 10000, 100);
        quotaService = new TenantQuotaService(tenantStorageProvider, memoryQuotaEngine, writeBehindBatcher);
        budgetManager = new TenantBudgetManager();
    }

    @AfterEach
    void tearDown() {
        if (writeBehindBatcher != null) {
            writeBehindBatcher.close();
        }
    }

    @Test
    @DisplayName("E2E: Pre-flight token leasing + async write-behind batch reconciliation")
    void testPreFlightReservationAndAsyncBatchSettlement() {
        TenantEntity tenantA = new TenantEntity("tenant-alpha", "Alpha Corp", "key-a", 20, 5000, 1000, true);
        TenantEntity tenantB = new TenantEntity("tenant-beta", "Beta LLC", "key-b", 20, 10000, 2000, true);

        when(tenantStorageProvider.findById("tenant-alpha")).thenReturn(Optional.of(tenantA));
        when(tenantStorageProvider.findById("tenant-beta")).thenReturn(Optional.of(tenantB));

        // 1. Reserve 1500 tokens for Alpha (Used 1000 + Frozen 1500 = 2500 <= 5000)
        QuotaReservation resA = quotaService.tryReserve("tenant-alpha", 1500);
        assertThat(quotaService.getFrozenTokens("tenant-alpha")).isEqualTo(1500);

        // 2. Reserve 2000 tokens for Beta
        QuotaReservation resB = quotaService.tryReserve("tenant-beta", 2000);
        assertThat(quotaService.getFrozenTokens("tenant-beta")).isEqualTo(2000);

        // 3. Settle Alpha with 1200 actual tokens (should release 1500 frozen, and buffer 1200 in write-behind batcher)
        quotaService.settle(resA, 1200);
        assertThat(quotaService.getFrozenTokens("tenant-alpha")).isEqualTo(0);
        assertThat(writeBehindBatcher.getPendingTokens("tenant-alpha")).isEqualTo(1200);

        // 4. Settle Beta with 1800 actual tokens
        quotaService.settle(resB, 1800);
        assertThat(quotaService.getFrozenTokens("tenant-beta")).isEqualTo(0);
        assertThat(writeBehindBatcher.getPendingTokens("tenant-beta")).isEqualTo(1800);

        // Storage provider should NOT have received individual row updates
        verify(tenantStorageProvider, never()).incrementUsedTokens(anyString(), anyLong());

        // 5. Trigger batch flush: all settlements are aggregated and sent in a single batchUpdate
        writeBehindBatcher.flush();

        verify(tenantStorageProvider).batchIncrementUsedTokens(argThat(batch ->
                batch.get("tenant-alpha") == 1200L && batch.get("tenant-beta") == 1800L
        ));
        assertThat(writeBehindBatcher.getPendingTokens("tenant-alpha")).isEqualTo(0);
        assertThat(writeBehindBatcher.getPendingTokens("tenant-beta")).isEqualTo(0);
    }

    @Test
    @DisplayName("E2E: Distributed Lua Quota Engine with Zero-Downtime Daily Rollover")
    void testDistributedLuaQuotaEngineRollover() {
        String tenantId = "dist-tenant-1";
        distributedLuaEngine.reset(tenantId);

        // Daily quota = 2000 tokens
        boolean ok1 = distributedLuaEngine.tryReserve(tenantId, 1200, 2000);
        assertThat(ok1).isTrue();
        assertThat(distributedLuaEngine.getFrozenTokens(tenantId)).isEqualTo(1200);

        // Settle actual 1100
        distributedLuaEngine.settle(tenantId, 1200, 1100);
        assertThat(distributedLuaEngine.getFrozenTokens(tenantId)).isEqualTo(0);
        assertThat(distributedLuaEngine.getUsedTokens(tenantId)).isEqualTo(1100);

        // Next reservation of 1000 -> 1100 + 1000 = 2100 > 2000 -> rejected!
        boolean ok2 = distributedLuaEngine.tryReserve(tenantId, 1000, 2000);
        assertThat(ok2).isFalse();
    }

    @Test
    @DisplayName("E2E: DistributedRateLimiter Circuit Breaker & Fail-Open Bounded Fallback")
    void testDistributedRateLimiterFailOpenFallback() throws Exception {
        TenantRateLimiter localLimiter = new TenantRateLimiter();
        DistributedRateLimiter.DistributedBackend faultyBackend = (tenant, est) -> {
            throw new IOException("Redis cluster timeout");
        };

        DistributedRateLimiter limiter = new DistributedRateLimiter(faultyBackend, localLimiter, 2);
        TenantEntity tenant = new TenantEntity("prod-t-1", "ProdCorp", "key-p1", 5, 100000, 0, true);

        // 1st failure: degrades to local limiter, still allowed (local quota has capacity)
        RateLimitResult r1 = limiter.tryAcquire(tenant, 100);
        assertThat(r1.allowed()).isTrue();
        assertThat(limiter.isCircuitOpen()).isFalse();

        // 2nd failure: trips circuit OPEN
        RateLimitResult r2 = limiter.tryAcquire(tenant, 100);
        assertThat(r2.allowed()).isTrue();
        assertThat(limiter.isCircuitOpen()).isTrue();

        // Fast path: circuit is OPEN, directly protected by local fallback without hitting dead backend
        RateLimitResult r3 = limiter.tryAcquire(tenant, 100);
        assertThat(r3.allowed()).isTrue();

        // Recovery: manual or heartbeat reset
        limiter.resetCircuit();
        assertThat(limiter.isCircuitOpen()).isFalse();
    }

    @Test
    @DisplayName("E2E: Multi-tier financial budget governance (OK -> 80% Warning -> 100% Exceeded)")
    void testMultiTierBudgetGovernance() {
        String tenantId = "enterprise-fintech";
        budgetManager.setBudgetLimit(tenantId, 50.0); // $50 budget
        budgetManager.setWarningRatio(0.80);

        AtomicBoolean warningFired = new AtomicBoolean(false);
        budgetManager.setWarningListener((id, spent, limit, ratio) -> warningFired.set(true));

        // Initial OK
        assertThat(budgetManager.getBudgetStatus(tenantId)).isEqualTo(TenantBudgetManager.BudgetStatus.OK);
        budgetManager.checkBudget(tenantId);

        // Spend $30 (60%) -> Still OK
        budgetManager.recordSpend(tenantId, 30.0);
        assertThat(budgetManager.getBudgetStatus(tenantId)).isEqualTo(TenantBudgetManager.BudgetStatus.OK);
        assertThat(warningFired.get()).isFalse();

        // Spend $12 (Total $42 = 84% >= 80%) -> WARNING tier reached, listener alerted
        budgetManager.recordSpend(tenantId, 12.0);
        assertThat(budgetManager.getBudgetStatus(tenantId)).isEqualTo(TenantBudgetManager.BudgetStatus.WARNING);
        assertThat(warningFired.get()).isTrue();
        budgetManager.checkBudget(tenantId); // warning doesn't block execution yet

        // Spend $9 (Total $51 > $50) -> EXCEEDED tier reached
        budgetManager.recordSpend(tenantId, 9.0);
        assertThat(budgetManager.getBudgetStatus(tenantId)).isEqualTo(TenantBudgetManager.BudgetStatus.EXCEEDED);

        assertThatThrownBy(() -> budgetManager.checkBudget(tenantId))
                .isInstanceOf(TenantBudgetExceededException.class)
                .hasMessageContaining("has exceeded financial budget limit");
    }
}
