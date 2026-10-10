package com.enterprise.llm.guard;

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
}
