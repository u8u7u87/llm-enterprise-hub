package com.enterprise.llm.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaStorageEngineTest {

    static Stream<QuotaStorageEngine> provideEngines() {
        return Stream.of(
                new InMemoryQuotaStorageEngine(),
                new DistributedLuaQuotaEngine()
        );
    }

    @ParameterizedTest
    @MethodSource("provideEngines")
    @DisplayName("Should successfully reserve, settle, and release tokens atomically")
    void testReservationLifecycle(QuotaStorageEngine engine) {
        String tenantId = "tenant-test-1";
        engine.reset(tenantId);

        // Daily quota = 1000
        boolean r1 = engine.tryReserve(tenantId, 400, 1000);
        assertThat(r1).isTrue();
        assertThat(engine.getFrozenTokens(tenantId)).isEqualTo(400);
        assertThat(engine.getUsedTokens(tenantId)).isEqualTo(0);

        // 2nd reserve: 500 tokens -> total committed = 400 + 500 = 900 <= 1000
        boolean r2 = engine.tryReserve(tenantId, 500, 1000);
        assertThat(r2).isTrue();
        assertThat(engine.getFrozenTokens(tenantId)).isEqualTo(900);

        // 3rd reserve: 200 tokens -> 900 + 200 = 1100 > 1000 -> rejected!
        boolean r3 = engine.tryReserve(tenantId, 200, 1000);
        assertThat(r3).isFalse();
        assertThat(engine.getFrozenTokens(tenantId)).isEqualTo(900);

        // Settle 1st reservation: estimated 400, actual 350
        engine.settle(tenantId, 400, 350);
        assertThat(engine.getFrozenTokens(tenantId)).isEqualTo(500);
        assertThat(engine.getUsedTokens(tenantId)).isEqualTo(350);

        // Release 2nd reservation on error: estimated 500
        engine.release(tenantId, 500);
        assertThat(engine.getFrozenTokens(tenantId)).isEqualTo(0);
        assertThat(engine.getUsedTokens(tenantId)).isEqualTo(350);

        // Now total committed = 350 + 0 + 200 = 550 <= 1000, 3rd reserve should succeed now
        boolean r4 = engine.tryReserve(tenantId, 200, 1000);
        assertThat(r4).isTrue();
        assertThat(engine.getFrozenTokens(tenantId)).isEqualTo(200);
    }

    @ParameterizedTest
    @MethodSource("provideEngines")
    @DisplayName("Unlimited quota (<= 0) should always be granted")
    void testUnlimitedQuota(QuotaStorageEngine engine) {
        String tenantId = "unlimited-tenant";
        engine.reset(tenantId);

        assertThat(engine.tryReserve(tenantId, 50000, 0)).isTrue();
        assertThat(engine.tryReserve(tenantId, 100000, -1)).isTrue();
    }
}
