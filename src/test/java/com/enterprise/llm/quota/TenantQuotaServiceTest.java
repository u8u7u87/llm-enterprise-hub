package com.enterprise.llm.quota;

import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantQuotaServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    private TenantQuotaService quotaService;

    @BeforeEach
    void setUp() {
        quotaService = new TenantQuotaService(tenantRepository);
    }

    @Test
    void shouldReserveQuotaSuccessfullyWhenBalanceSufficient() {
        TenantEntity tenant = new TenantEntity("t-1", "Corp", "key", 10, 10000, 1000, true);
        when(tenantRepository.findById("t-1")).thenReturn(Optional.of(tenant));

        QuotaReservation reservation = quotaService.tryReserve("t-1", 500);

        assertThat(reservation).isNotNull();
        assertThat(reservation.tenantId()).isEqualTo("t-1");
        assertThat(reservation.estimatedTokens()).isEqualTo(500);
        assertThat(quotaService.getFrozenTokens("t-1")).isEqualTo(500);
    }

    @Test
    void shouldRejectReservationWhenQuotaExceeded() {
        TenantEntity tenant = new TenantEntity("t-1", "Corp", "key", 10, 10000, 9600, true);
        when(tenantRepository.findById("t-1")).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> quotaService.tryReserve("t-1", 500))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("Daily token quota exceeded for tenant");

        assertThat(quotaService.getFrozenTokens("t-1")).isEqualTo(0);
    }

    @Test
    void shouldAccountForFrozenTokensInConcurrentReservations() {
        TenantEntity tenant = new TenantEntity("t-1", "Corp", "key", 10, 1000, 500, true);
        when(tenantRepository.findById("t-1")).thenReturn(Optional.of(tenant));

        // 1st reservation: 300 tokens -> total committed: 500 + 300 = 800 <= 1000
        QuotaReservation res1 = quotaService.tryReserve("t-1", 300);
        assertThat(res1).isNotNull();

        // 2nd reservation: 300 tokens -> total committed: 500 + 300 + 300 = 1100 > 1000 -> rejected!
        assertThatThrownBy(() -> quotaService.tryReserve("t-1", 300))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void shouldSettleReservationAndUpdateDatabase() {
        TenantEntity tenant = new TenantEntity("t-1", "Corp", "key", 10, 10000, 1000, true);
        when(tenantRepository.findById("t-1")).thenReturn(Optional.of(tenant));

        QuotaReservation reservation = quotaService.tryReserve("t-1", 800);
        assertThat(quotaService.getFrozenTokens("t-1")).isEqualTo(800);

        quotaService.settle(reservation, 450);

        assertThat(quotaService.getFrozenTokens("t-1")).isEqualTo(0);
        verify(tenantRepository).incrementUsedTokens("t-1", 450);
    }

    @Test
    void shouldReleaseReservationOnError() {
        TenantEntity tenant = new TenantEntity("t-1", "Corp", "key", 10, 10000, 1000, true);
        when(tenantRepository.findById("t-1")).thenReturn(Optional.of(tenant));

        QuotaReservation reservation = quotaService.tryReserve("t-1", 600);
        assertThat(quotaService.getFrozenTokens("t-1")).isEqualTo(600);

        quotaService.release(reservation);
        assertThat(quotaService.getFrozenTokens("t-1")).isEqualTo(0);
        verify(tenantRepository, never()).incrementUsedTokens(anyString(), anyLong());
    }
}
