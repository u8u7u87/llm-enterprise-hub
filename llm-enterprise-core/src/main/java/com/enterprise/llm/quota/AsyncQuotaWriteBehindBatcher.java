package com.enterprise.llm.quota;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import com.enterprise.llm.tenant.TenantStorageProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-throughput, write-behind asynchronous batcher for tenant token quota reconciliation.
 * Aggregates post-flight actual token settlements in-memory and flushes micro-batches
 * to the underlying storage provider to avoid database row lock contention.
 */
public class AsyncQuotaWriteBehindBatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncQuotaWriteBehindBatcher.class);

    private final TenantStorageProvider storageProvider;
    private final ConcurrentHashMap<String, LongAdder> pendingAccumulator = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final int flushIntervalMs;
    private final int batchSizeThreshold;
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    public AsyncQuotaWriteBehindBatcher(TenantStorageProvider storageProvider) {
        this(storageProvider, 200, 100);
    }

    public AsyncQuotaWriteBehindBatcher(TenantStorageProvider storageProvider, int flushIntervalMs, int batchSizeThreshold) {
        this.storageProvider = storageProvider;
        this.flushIntervalMs = Math.max(50, flushIntervalMs);
        this.batchSizeThreshold = Math.max(10, batchSizeThreshold);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("quota-write-behind-flusher").factory());

        this.scheduler.scheduleWithFixedDelay(
                this::safeFlush,
                this.flushIntervalMs,
                this.flushIntervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Record post-flight actual tokens consumed by a tenant.
     */
    public void recordSettlement(String tenantId, long actualTokens) {
        if (tenantId == null || actualTokens <= 0 || !isRunning.get()) {
            return;
        }

        pendingAccumulator.computeIfAbsent(tenantId, k -> new LongAdder()).add(actualTokens);

        if (pendingAccumulator.size() >= batchSizeThreshold) {
            scheduler.execute(this::safeFlush);
        }
    }

    /**
     * Drains the pending accumulator and persists the batch to the storage provider.
     * Returns the flushed tenant-tokens map.
     */
    public synchronized Map<String, Long> flush() {
        if (pendingAccumulator.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> batch = new HashMap<>();
        pendingAccumulator.forEach((tenantId, adder) -> {
            long tokens = adder.sumThenReset();
            if (tokens > 0) {
                batch.put(tenantId, tokens);
            }
        });

        // Clean up empty adders
        pendingAccumulator.entrySet().removeIf(e -> e.getValue().sum() == 0);

        if (!batch.isEmpty() && storageProvider != null) {
            try {
                storageProvider.batchIncrementUsedTokens(batch);
                log.debug("Flushed write-behind token batch: {} tenants, {} total tokens",
                        batch.size(), batch.values().stream().mapToLong(Long::longValue).sum());
            } catch (Exception e) {
                log.error("Failed to flush write-behind quota batch: {}", e.getMessage(), e);
                // Re-accumulate back in case of storage failure to avoid loss
                batch.forEach((tenantId, tokens) ->
                        pendingAccumulator.computeIfAbsent(tenantId, k -> new LongAdder()).add(tokens)
                );
            }
        }
        return batch;
    }

    private void safeFlush() {
        try {
            flush();
        } catch (Throwable t) {
            log.error("Unexpected error in background quota flusher: {}", t.getMessage(), t);
        }
    }

    public int getPendingTenantCount() {
        return pendingAccumulator.size();
    }

    public long getPendingTokens(String tenantId) {
        LongAdder adder = pendingAccumulator.get(tenantId);
        return adder != null ? adder.sum() : 0L;
    }

    @Override
    @PreDestroy
    public void close() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("Shutting down AsyncQuotaWriteBehindBatcher, performing final flush...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
            flush();
        }
    }
}
