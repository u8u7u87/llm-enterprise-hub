package com.enterprise.llm.quota;

import java.util.UUID;

public record QuotaReservation(
        String reservationId,
        String tenantId,
        int estimatedTokens
) {
    public static QuotaReservation of(String tenantId, int estimatedTokens) {
        return new QuotaReservation(UUID.randomUUID().toString(), tenantId, estimatedTokens);
    }
}
