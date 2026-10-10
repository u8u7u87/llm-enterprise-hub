package com.enterprise.llm.quota;

import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TenantQuotaService {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaService.class);

    private final TenantRepository tenantRepository;
    private final ConcurrentHashMap<String, AtomicLong> frozenTokensMap = new ConcurrentHashMap<>();

    public TenantQuotaService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public synchronized QuotaReservation tryReserve(String tenantId, int estimatedTokens) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        if (tenant.getDailyTokenQuota() > 0) {
            AtomicLong frozen = frozenTokensMap.computeIfAbsent(tenantId, k -> new AtomicLong(0));
            long totalCommitted = tenant.getUsedTokens() + frozen.get() + estimatedTokens;

            if (totalCommitted > tenant.getDailyTokenQuota()) {
                log.warn("Tenant {} quota exceeded. Used={}, Frozen={}, Requested={}, DailyQuota={}",
                        tenantId, tenant.getUsedTokens(), frozen.get(), estimatedTokens, tenant.getDailyTokenQuota());
                throw new QuotaExceededException("Daily token quota exceeded for tenant " + tenantId);
            }
            frozen.addAndGet(estimatedTokens);
        }

        return QuotaReservation.of(tenantId, estimatedTokens);
    }

    public synchronized void settle(QuotaReservation reservation, int actualTokens) {
        if (reservation == null) {
            return;
        }

        AtomicLong frozen = frozenTokensMap.get(reservation.tenantId());
        if (frozen != null) {
            long remaining = frozen.addAndGet(-reservation.estimatedTokens());
            if (remaining < 0) {
                frozen.set(0);
            }
        }

        if (actualTokens > 0) {
            tenantRepository.incrementUsedTokens(reservation.tenantId(), actualTokens);
            log.debug("Settled {} actual tokens for tenant {}, released reservation {}",
                    actualTokens, reservation.tenantId(), reservation.estimatedTokens());
        }
    }

    public synchronized void release(QuotaReservation reservation) {
        if (reservation == null) {
            return;
        }

        AtomicLong frozen = frozenTokensMap.get(reservation.tenantId());
        if (frozen != null) {
            long remaining = frozen.addAndGet(-reservation.estimatedTokens());
            if (remaining < 0) {
                frozen.set(0);
            }
        }
        log.debug("Released unused quota reservation {} for tenant {}",
                reservation.estimatedTokens(), reservation.tenantId());
    }

    public long getFrozenTokens(String tenantId) {
        AtomicLong frozen = frozenTokensMap.get(tenantId);
        return frozen != null ? Math.max(0, frozen.get()) : 0;
    }
}
