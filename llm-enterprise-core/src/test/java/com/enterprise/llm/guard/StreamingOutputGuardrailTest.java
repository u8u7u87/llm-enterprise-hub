package com.enterprise.llm.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingOutputGuardrailTest {

    @Test
    @DisplayName("Should pass safe streaming text smoothly")
    void shouldPassSafeStreamingText() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.BLOCK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        StreamingOutputGuardrail.InspectionResult r1 = session.inspect("Hello ");
        StreamingOutputGuardrail.InspectionResult r2 = session.inspect("World! How can I assist you today?");

        assertThat(r1.blocked()).isFalse();
        assertThat(r1.sanitizedText()).isEqualTo("Hello ");
        assertThat(r2.blocked()).isFalse();
        assertThat(r2.sanitizedText()).isEqualTo("World! How can I assist you today?");
    }

    @Test
    @DisplayName("Should block cross-chunk phone number leak in BLOCK mode")
    void shouldBlockCrossChunkPhoneNumber() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.BLOCK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        // Chunk 1 has part of the phone number
        StreamingOutputGuardrail.InspectionResult r1 = session.inspect("负责人手机号是: 139123");
        assertThat(r1.blocked()).isFalse();

        // Chunk 2 completes the 11-digit phone number
        assertThatThrownBy(() -> session.inspect("45678，请尽快联系。"))
                .isInstanceOf(StreamingGuardrailException.class)
                .hasMessageContaining("PHONE_NUMBER");
    }

    @Test
    @DisplayName("Should block cross-chunk API Key leak in BLOCK mode")
    void shouldBlockCrossChunkApiKey() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.BLOCK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        session.inspect("Use my key: sk-abcdef1234567");

        assertThatThrownBy(() -> session.inspect("8901234567890 to authenticate."))
                .isInstanceOf(StreamingGuardrailException.class)
                .hasMessageContaining("API_KEY");
    }

    @Test
    @DisplayName("Should mask cross-chunk phone number in MASK mode")
    void shouldMaskCrossChunkPhoneNumber() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.MASK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        StreamingOutputGuardrail.InspectionResult r1 = session.inspect("电话：138000");
        assertThat(r1.sanitizedText()).isEqualTo("电话：138000");

        StreamingOutputGuardrail.InspectionResult r2 = session.inspect("00000 欢迎来电");
        assertThat(r2.sanitizedText()).contains("***** 欢迎来电");

        // Single chunk full masking test
        StreamingOutputGuardrail.StreamSession session2 = guardrail.newSession();
        StreamingOutputGuardrail.InspectionResult rFull = session2.inspect("手机号: 13912345678");
        assertThat(rFull.sanitizedText()).isEqualTo("手机号: 1**********");
    }
}

