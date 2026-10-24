package com.enterprise.llm.quota;

import com.enterprise.llm.tenant.TenantStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncQuotaWriteBehindBatcherTest {

    @Mock
    private TenantStorageProvider storageProvider;

    private AsyncQuotaWriteBehindBatcher batcher;

    @BeforeEach
    void setUp() {
        // High flushIntervalMs (10s) to manually control flush in tests
        batcher = new AsyncQuotaWriteBehindBatcher(storageProvider, 10000, 100);
    }

    @AfterEach
    void tearDown() {
        if (batcher != null) {
            batcher.close();
        }
    }

    @Test
    void shouldAccumulateAndFlushTokensInBatch() {
        batcher.recordSettlement("tenant-1", 150);
        batcher.recordSettlement("tenant-1", 250);
        batcher.recordSettlement("tenant-2", 300);

        assertThat(batcher.getPendingTokens("tenant-1")).isEqualTo(400);
        assertThat(batcher.getPendingTokens("tenant-2")).isEqualTo(300);

        Map<String, Long> flushed = batcher.flush();

        assertThat(flushed).containsEntry("tenant-1", 400L);
        assertThat(flushed).containsEntry("tenant-2", 300L);

        ArgumentCaptor<Map<String, Long>> captor = ArgumentCaptor.forClass(Map.class);
        verify(storageProvider).batchIncrementUsedTokens(captor.capture());
        assertThat(captor.getValue()).containsEntry("tenant-1", 400L);
        assertThat(captor.getValue()).containsEntry("tenant-2", 300L);

        // After flush, pending tokens should be cleared
        assertThat(batcher.getPendingTokens("tenant-1")).isEqualTo(0);
        assertThat(batcher.getPendingTokens("tenant-2")).isEqualTo(0);
    }

    @Test
    void shouldHandleEmptyFlushGracefully() {
        Map<String, Long> flushed = batcher.flush();
        assertThat(flushed).isEmpty();
        verifyNoInteractions(storageProvider);
    }

    @Test
    void shouldIgnoreNullOrNegativeValues() {
        batcher.recordSettlement(null, 100);
        batcher.recordSettlement("tenant-1", 0);
        batcher.recordSettlement("tenant-1", -50);

        assertThat(batcher.getPendingTenantCount()).isEqualTo(0);
        Map<String, Long> flushed = batcher.flush();
        assertThat(flushed).isEmpty();
    }

    @Test
    void shouldPerformFinalFlushOnClose() {
        batcher.recordSettlement("tenant-close", 500);
        batcher.close();

        verify(storageProvider).batchIncrementUsedTokens(argThat(map ->
                map.containsKey("tenant-close") && map.get("tenant-close") == 500L
        ));
    }
}
