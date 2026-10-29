package com.enterprise.llm.quota;

import com.enterprise.llm.auth.TenantSecurityContext;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.router.FailoverRoutingService;
import com.enterprise.llm.service.ChatExecutionService;
import com.enterprise.llm.tenant.TenantEntity;
import com.enterprise.llm.token.TokenEstimator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatQuotaIntegrationTest {

    @Mock
    private FailoverRoutingService failoverRoutingService;

    @Mock
    private TenantQuotaService tenantQuotaService;

    private TokenEstimator tokenEstimator;
    private ChatExecutionService chatExecutionService;

    @BeforeEach
    void setUp() {
        tokenEstimator = new TokenEstimator();
        chatExecutionService = new ChatExecutionService(
                failoverRoutingService,
                tenantQuotaService,
                tokenEstimator
        );
    }

    @AfterEach
    void tearDown() {
        TenantSecurityContext.clear();
    }

    @Test
    void shouldExecuteSyncAndSettleQuota() {
        TenantEntity tenant = new TenantEntity("t-quota", "Quota Corp", "key", 10, 5000, 0, true);
        TenantSecurityContext.setCurrentTenant(tenant);

        QuotaReservation reservation = QuotaReservation.of("t-quota", 600);

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(new ChatMessage("user", "Hello world")),
                false,
                0.7
        );

        ChatCompletionResponse response = new ChatCompletionResponse(
                "chat-1", "chat.completion", 123456L, "qwen2.5:0.5b",
                List.of(new ChatCompletionResponse.Choice(0, new ChatMessage("assistant", "Hi there"), "stop")),
                new Usage(10, 5, 15)
        );

        when(failoverRoutingService.chatCompletionWithFailover(request)).thenReturn(response);

        ChatCompletionResponse result = chatExecutionService.executeSync(request, reservation);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo("chat-1");
        verify(tenantQuotaService).settle(reservation, 15);
    }

    @Test
    void shouldReleaseQuotaWhenSyncExecutionFails() {
        QuotaReservation reservation = QuotaReservation.of("t-quota", 600);

        ChatCompletionRequest request = new ChatCompletionRequest(
                "qwen2.5:0.5b",
                List.of(new ChatMessage("user", "Hello world")),
                false,
                0.7
        );

        when(failoverRoutingService.chatCompletionWithFailover(request))
                .thenThrow(new RuntimeException("Upstream failed"));

        assertThatThrownBy(() -> chatExecutionService.executeSync(request, reservation))
                .isInstanceOf(RuntimeException.class);

        verify(tenantQuotaService).release(reservation);
    }
}
