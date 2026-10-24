package com.enterprise.llm.quota;

import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Enterprise multi-tenant token quota service.
 * Supports lock-free pre-flight reservation, write-behind asynchronous reconciliation,
 * and pluggable QuotaStorageEngine (In-memory CAS or Distributed Redis Lua).
 */
@Service
public class TenantQuotaService {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaService.class);

    private final TenantStorageProvider tenantRepository;
    private final QuotaStorageEngine quotaStorageEngine;
    private final AsyncQuotaWriteBehindBatcher writeBehindBatcher;

    public TenantQuotaService(TenantStorageProvider tenantRepository) {
        this(tenantRepository, new InMemoryQuotaStorageEngine(), null);
    }

    @Autowired
    public TenantQuotaService(TenantStorageProvider tenantRepository,
                              @Autowired(required = false) QuotaStorageEngine quotaStorageEngine,
                              @Autowired(required = false) AsyncQuotaWriteBehindBatcher writeBehindBatcher) {
        this.tenantRepository = tenantRepository;
        this.quotaStorageEngine = quotaStorageEngine != null ? quotaStorageEngine : new InMemoryQuotaStorageEngine();
        this.writeBehindBatcher = writeBehindBatcher;
    }

    /**
     * Pre-flight atomic token reservation check.
     */
    public QuotaReservation tryReserve(String tenantId, int estimatedTokens) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        if (tenant.getDailyTokenQuota() > 0) {
            long currentUsed = tenant.getUsedTokens();
            long frozen = quotaStorageEngine.getFrozenTokens(tenantId);
            long totalCommitted = currentUsed + frozen + estimatedTokens;

            if (totalCommitted > tenant.getDailyTokenQuota()) {
                log.warn("Tenant {} quota exceeded. Used={}, Frozen={}, Requested={}, DailyQuota={}",
                        tenantId, currentUsed, frozen, estimatedTokens, tenant.getDailyTokenQuota());
                throw new QuotaExceededException("Daily token quota exceeded for tenant " + tenantId);
            }
            // Freeze tokens in the underlying engine
            quotaStorageEngine.tryReserve(tenantId, estimatedTokens, 0);
        }

        return QuotaReservation.of(tenantId, estimatedTokens);
    }

    /**
     * Post-flight token settlement. Unfreezes the reservation and reconciles actual tokens.
     */
    public void settle(QuotaReservation reservation, int actualTokens) {
        if (reservation == null) {
            return;
        }

        quotaStorageEngine.release(reservation.tenantId(), reservation.estimatedTokens());

        if (actualTokens > 0) {
            if (writeBehindBatcher != null) {
                // High-throughput async write-behind path
                writeBehindBatcher.recordSettlement(reservation.tenantId(), actualTokens);
                log.debug("Buffered {} tokens in write-behind batcher for tenant {}",
                        actualTokens, reservation.tenantId());
            } else {
                // Direct storage update fallback
                tenantRepository.incrementUsedTokens(reservation.tenantId(), actualTokens);
                log.debug("Directly settled {} actual tokens for tenant {}",
                        actualTokens, reservation.tenantId());
            }
        }
    }

    /**
     * Releases a reservation when request execution fails or is cancelled.
     */
    public void release(QuotaReservation reservation) {
        if (reservation == null) {
            return;
        }

        quotaStorageEngine.release(reservation.tenantId(), reservation.estimatedTokens());
        log.debug("Released unused quota reservation {} for tenant {}",
                reservation.estimatedTokens(), reservation.tenantId());
    }

    public long getFrozenTokens(String tenantId) {
        return quotaStorageEngine.getFrozenTokens(tenantId);
    }

    public QuotaStorageEngine getQuotaStorageEngine() {
        return quotaStorageEngine;
    }

    public AsyncQuotaWriteBehindBatcher getWriteBehindBatcher() {
        return writeBehindBatcher;
    }
}
