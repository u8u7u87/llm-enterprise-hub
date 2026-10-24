package com.enterprise.llm.e2e;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatCompletionResponse;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.model.Usage;
import com.enterprise.llm.resilience.AdaptiveJitteredRetryPolicy;
import com.enterprise.llm.router.canary.CanaryRoutingService;
import com.enterprise.llm.router.canary.CanaryRule;
import com.enterprise.llm.router.shadow.ShadowComparisonRecord;
import com.enterprise.llm.router.shadow.ShadowTrafficMirror;
import com.enterprise.llm.router.shadow.ShadowTrafficRule;
import com.enterprise.llm.template.MissingTemplateVariableException;
import com.enterprise.llm.template.PromptTemplate;
import com.enterprise.llm.template.PromptTemplateRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Phase9TrafficGovernanceE2ETest {

    @Mock
    private LlmClient shadowClient;

    private CanaryRoutingService canaryService;
    private ShadowTrafficMirror shadowMirror;
    private PromptTemplateRegistry templateRegistry;
    private AdaptiveJitteredRetryPolicy retryPolicy;

    @BeforeEach
    void setUp() {
        canaryService = new CanaryRoutingService();
        shadowMirror = new ShadowTrafficMirror();
        templateRegistry = new PromptTemplateRegistry();
        retryPolicy = new AdaptiveJitteredRetryPolicy(3, 50, 1000, AdaptiveJitteredRetryPolicy.JitterStrategy.FULL_JITTER);
    }

    @AfterEach
    void tearDown() {
        if (shadowMirror != null) {
            shadowMirror.close();
        }
    }

    @Test
    @DisplayName("E2E: Canary routing with sticky session hashing and tenant override")
    void testCanaryRoutingE2E() {
        // Global rule: 40% canary to deepseek-r1 with session stickiness
        canaryService.setRule(new CanaryRule("gpt-4o", "deepseek-r1", 40, false, true));

        // For a fixed session, resolveModel must return the exact same model deterministically
        String sessA = "sess-user-alpha-99";
        String resolved1 = canaryService.resolveModel("gpt-4o", "tenant-1", sessA);
        for (int i = 0; i < 20; i++) {
            assertThat(canaryService.resolveModel("gpt-4o", "tenant-1", sessA)).isEqualTo(resolved1);
        }

        // Specific VIP tenant gets 100% canary override
        canaryService.setTenantOverride("vip-fintech", "gpt-4o", "deepseek-r1");
        assertThat(canaryService.resolveModel("gpt-4o", "vip-fintech", "any-sess")).isEqualTo("deepseek-r1");
    }

    @Test
    @DisplayName("E2E: Async shadow traffic mirroring and performance comparison")
    void testShadowTrafficMirroringE2E() throws InterruptedException {
        shadowMirror.setRule(new ShadowTrafficRule("gpt-4o", "qwen2.5:72b", 1.0));

        ChatCompletionRequest primaryReq = new ChatCompletionRequest(
                "gpt-4o", List.of(ChatMessage.user("Summarize quarterly report")), false, 0.7
        );
        ChatCompletionResponse primaryResp = new ChatCompletionResponse(
                "resp-prim", "chat.completion", 1000L, "gpt-4o",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Primary summary"), "stop")),
                new Usage(100, 200, 300)
        );

        ChatCompletionResponse shadowResp = new ChatCompletionResponse(
                "resp-shad", "chat.completion", 1000L, "qwen2.5:72b",
                List.of(new ChatCompletionResponse.Choice(0, ChatMessage.assistant("Shadow candidate summary"), "stop")),
                new Usage(100, 180, 280)
        );

        when(shadowClient.chatCompletion(argThat(r -> "qwen2.5:72b".equals(r.model()))))
                .thenReturn(shadowResp);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ShadowComparisonRecord> recordRef = new AtomicReference<>();
        shadowMirror.setComparisonListener(rec -> {
            recordRef.set(rec);
            latch.countDown();
        });

        shadowMirror.maybeMirrorAsync(primaryReq, primaryResp, 65, "tenant-corp", "trace-shadow-01", shadowClient);

        boolean finished = latch.await(3, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        ShadowComparisonRecord record = recordRef.get();
        assertThat(record).isNotNull();
        assertThat(record.sourceModel()).isEqualTo("gpt-4o");
        assertThat(record.shadowModel()).isEqualTo("qwen2.5:72b");
        assertThat(record.primaryTokens()).isEqualTo(300);
        assertThat(record.shadowTokens()).isEqualTo(280);
        assertThat(record.shadowSuccess()).isTrue();
    }

    @Test
    @DisplayName("E2E: Enterprise Prompt Template Registry with versioning, validation, and tenant override")
    void testPromptTemplateRegistryE2E() {
        PromptTemplate baseTemplate = new PromptTemplate(
                "customer_triage",
                "v1",
                "Triage customer inquiry for {{domain}}: {{inquiry}}",
                Set.of("domain", "inquiry"),
                "Base customer triage prompt"
        );
        templateRegistry.registerTemplate(baseTemplate, true);

        // Render base
        Map<String, String> vars = Map.of("domain", "Logistics", "inquiry", "Where is my parcel?");
        String renderedBase = templateRegistry.render("customer_triage", "v1", "tenant-general", vars);
        assertThat(renderedBase).isEqualTo("Triage customer inquiry for Logistics: Where is my parcel?");

        // Validation test
        assertThatThrownBy(() -> templateRegistry.render("customer_triage", "v1", "tenant-general", Map.of("domain", "Finance")))
                .isInstanceOf(MissingTemplateVariableException.class)
                .hasMessageContaining("Missing required prompt variable: inquiry");

        // Tenant custom override
        PromptTemplate tenantCustom = new PromptTemplate(
                "customer_triage",
                "custom-v2",
                "Enterprise Banking Triage for {{domain}}. Urgent case: {{inquiry}}",
                Set.of("domain", "inquiry"),
                "Custom banking prompt"
        );
        templateRegistry.registerTenantOverride("tenant-bank", tenantCustom);

        String renderedBank = templateRegistry.render("customer_triage", null, "tenant-bank", vars);
        assertThat(renderedBank).isEqualTo("Enterprise Banking Triage for Logistics. Urgent case: Where is my parcel?");
    }

    @Test
    @DisplayName("E2E: Adaptive Jitter Retry policy bounded backoff")
    void testAdaptiveJitterRetryPolicyE2E() {
        assertThat(retryPolicy.shouldRetry(new com.enterprise.llm.exception.LlmRateLimitException("Too Many Requests"), 1)).isTrue();
        assertThat(retryPolicy.shouldRetry(new com.enterprise.llm.exception.LlmRateLimitException("Too Many Requests"), 4)).isFalse();

        long backoff = retryPolicy.computeBackoffMs(2);
        assertThat(backoff).isBetween(0L, 1000L);
    }
}
