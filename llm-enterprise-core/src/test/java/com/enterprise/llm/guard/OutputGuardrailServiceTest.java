package com.enterprise.llm.guard;

import com.enterprise.llm.guard.dlp.DlpMaskType;
import com.enterprise.llm.guard.dlp.DlpRule;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OutputGuardrailServiceTest {

    private OutputGuardrailService guardrailService;

    @BeforeEach
    void setUp() {
        guardrailService = new OutputGuardrailService();
    }

    @Test
    void shouldRedactOpenAiApiKey() {
        String output = "Here is the OpenAI key you requested: sk-proj1234567890abcdefghijklmnOPQRSTUV";
        String sanitized = guardrailService.sanitize(output);

        assertThat(sanitized).doesNotContain("sk-proj1234567890");
        assertThat(sanitized).contains("[REDACTED_API_KEY]");
    }

    @Test
    void shouldRedactAwsAccessKey() {
        String output = "AWS credentials: AKIAIOSFODNN7EXAMPLE and secret";
        String sanitized = guardrailService.sanitize(output);

        assertThat(sanitized).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(sanitized).contains("[REDACTED_AWS_KEY]");
    }

    @Test
    void shouldRedactJwtToken() {
        String output = "Auth header: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String sanitized = guardrailService.sanitize(output);

        assertThat(sanitized).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertThat(sanitized).contains("[REDACTED_JWT]");
    }

    @Test
    void shouldRedactMobilePhone() {
        String output = "Contact customer support at 13812345678 for assistance.";
        String sanitized = guardrailService.sanitize(output);

        assertThat(sanitized).doesNotContain("13812345678");
        assertThat(sanitized).contains("[REDACTED_PHONE]");
    }

    @Test
    void shouldRedactEmailAddress() {
        String output = "Reach out to admin@enterprise-security.internal for access.";
        String sanitized = guardrailService.sanitize(output);

        assertThat(sanitized).doesNotContain("admin@enterprise-security.internal");
        assertThat(sanitized).contains("[REDACTED_EMAIL]");
    }

    @Test
    void shouldLeaveNormalTextUntouched() {
        String output = "Java 21 Virtual Threads offer lightweight concurrency without OS thread overhead.";
        String sanitized = guardrailService.sanitize(output);

        assertThat(sanitized).isEqualTo(output);
    }

    @Test
    void shouldSupportCustomDynamicDlpRule() {
        guardrailService.addDynamicRule(new DlpRule(
                "order_sn",
                "Order Serial Number",
                "ORD-[0-9]{8}",
                DlpMaskType.REPLACE_TAG,
                "[REDACTED_ORDER_SN]",
                true
        ));

        String output = "Order detail for ORD-20261010 created.";
        String sanitized = guardrailService.sanitize(output);

        assertThat(sanitized).contains("[REDACTED_ORDER_SN]");
        assertThat(sanitized).doesNotContain("ORD-20261010");
    }

    @Test
    void shouldThrowSecurityViolationWhenRuleMaskTypeIsBlock() {
        guardrailService.addDynamicRule(new DlpRule(
                "confidential_leak",
                "Top Secret",
                "(?i)internal_payroll_dump",
                DlpMaskType.BLOCK,
                "",
                true
        ));

        Assertions.assertThatThrownBy(() ->
                guardrailService.sanitize("Here is the internal_payroll_dump file")
        ).isInstanceOf(SecurityViolationException.class);
    }
}
