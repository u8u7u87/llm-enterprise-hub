package com.enterprise.llm.ratelimit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 毫秒级 60 秒环形滑动窗口限流器 (Dual Sliding Window: RPM + TPM)
 * 支持原子扣减预估 Token 与请求完成后多退少补校准。
 */
public class SlidingWindowRateLimiter {

    private static final int WINDOW_SECONDS = 60;

    private static class Slot {
        long secondTimestamp = 0;
        final AtomicInteger requests = new AtomicInteger(0);
        final AtomicLong tokens = new AtomicLong(0);

        void reset(long sec) {
            this.secondTimestamp = sec;
            this.requests.set(0);
            this.tokens.set(0);
        }
    }

    private final Slot[] slots = new Slot[WINDOW_SECONDS];
    private final ReentrantLock lock = new ReentrantLock();

    public SlidingWindowRateLimiter() {
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            slots[i] = new Slot();
        }
    }

    /**
     * 尝试获取 RPM 和 TPM 许可
     *
     * @param rpmLimit        每分钟请求数限制（<=0 表示不限制）
     * @param tpmLimit        每分钟 Token 数限制（<=0 表示不限制）
     * @param estimatedTokens 本次请求预估消耗 Token 数
     * @return 判定结果
     */
    public RateLimitResult tryAcquire(int rpmLimit, long tpmLimit, int estimatedTokens) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            long currentSec = now / 1000;

            // 1. 统计当前 60 秒窗口内的请求数与 Token 总量
            long totalRequests = 0;
            long totalTokens = 0;

            for (Slot slot : slots) {
                if (currentSec - slot.secondTimestamp < WINDOW_SECONDS) {
                    totalRequests += slot.requests.get();
                    totalTokens += slot.tokens.get();
                }
            }

            // 2. 校验 RPM
            if (rpmLimit > 0 && totalRequests + 1 > rpmLimit) {
                return RateLimitResult.rejected("RPM_EXCEEDED: current=" + totalRequests + ", limit=" + rpmLimit, 1000);
            }

            // 3. 校验 TPM
            if (tpmLimit > 0 && totalTokens + estimatedTokens > tpmLimit) {
                return RateLimitResult.rejected("TPM_EXCEEDED: current=" + (totalTokens + estimatedTokens) + ", limit=" + tpmLimit, 1000);
            }

            // 4. 批准并更新当前时间槽位
            Slot currentSlot = getSlot(currentSec);
            currentSlot.requests.incrementAndGet();
            currentSlot.tokens.addAndGet(Math.max(0, estimatedTokens));

            return RateLimitResult.granted();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 推理完成后校准 Token 消耗差额 (多退少补)
     *
     * @param actualTokens    模型调用实际消耗的 Token 数
     * @param estimatedTokens 原先预估的 Token 数
     */
    public void recordUsage(long actualTokens, long estimatedTokens) {
        long delta = actualTokens - estimatedTokens;
        if (delta == 0) {
            return;
        }

        lock.lock();
        try {
            long currentSec = System.currentTimeMillis() / 1000;
            Slot currentSlot = getSlot(currentSec);
            long updated = currentSlot.tokens.addAndGet(delta);
            if (updated < 0) {
                currentSlot.tokens.set(0);
            }
        } finally {
            lock.unlock();
        }
    }

    private Slot getSlot(long currentSec) {
        int index = (int) (currentSec % WINDOW_SECONDS);
        Slot slot = slots[index];
        if (slot.secondTimestamp != currentSec) {
            slot.reset(currentSec);
        }
        return slot;
    }
}
