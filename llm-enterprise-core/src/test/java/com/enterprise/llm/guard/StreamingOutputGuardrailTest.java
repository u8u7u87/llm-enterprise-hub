package com.enterprise.llm.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class StreamingOutputGuardrailTest {

    private static final String PHONE = "13800138000";

    @Test
    @DisplayName("Should pass safe streaming text without holding anything back")
    void shouldPassSafeStreamingText() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.BLOCK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        StreamingOutputGuardrail.InspectionResult r1 = session.inspect("Hello ");
        StreamingOutputGuardrail.InspectionResult r2 = session.inspect("World! How can I assist you today?");

        assertThat(r1.blocked()).isFalse();
        assertThat(r1.sanitizedText()).isEqualTo("Hello ");
        assertThat(r2.blocked()).isFalse();
        assertThat(r2.sanitizedText()).isEqualTo("World! How can I assist you today?");
        assertThat(session.flush()).isEmpty();
    }

    @Test
    @DisplayName("Should block cross-chunk phone number leak in BLOCK mode")
    void shouldBlockCrossChunkPhoneNumber() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.BLOCK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        // Chunk 1 has part of the phone number
        StreamingOutputGuardrail.InspectionResult r1 = session.inspect("负责人手机号是: 139123");
        assertThat(r1.blocked()).isFalse();
        // The ambiguous digit run must not be released before the violation is known
        assertThat(r1.sanitizedText()).isEqualTo("负责人手机号是: ");

        // Chunk 2 completes the 11-digit phone number
        assertThatThrownBy(() -> session.inspect("45678，请尽快联系。"))
                .isInstanceOf(StreamingGuardrailException.class)
                .hasMessageContaining("PHONE_NUMBER");
    }

    @Test
    @DisplayName("BLOCK mode must never release the digits it eventually blocks on")
    void blockModeShouldNotLeakPrefixOfBlockedSecret() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.BLOCK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        StringBuilder emitted = new StringBuilder();
        try {
            for (char c : ("电话" + PHONE).toCharArray()) {
                emitted.append(session.inspect(String.valueOf(c)).sanitizedText());
            }
            fail("Expected the guardrail to block the phone number");
        } catch (StreamingGuardrailException e) {
            assertThat(e.getMessage()).contains("PHONE_NUMBER");
        }

        assertThat(emitted.toString()).isEqualTo("电话");
    }

    @Test
    @DisplayName("Should block cross-chunk API Key leak in BLOCK mode")
    void shouldBlockCrossChunkApiKey() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.BLOCK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        StreamingOutputGuardrail.InspectionResult r1 = session.inspect("Use my key: sk-abcdef1234567");
        assertThat(r1.sanitizedText()).isEqualTo("Use my key: ");

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
        assertThat(r1.sanitizedText()).isEqualTo("电话：");

        StreamingOutputGuardrail.InspectionResult r2 = session.inspect("00000 欢迎来电");
        assertThat(r2.sanitizedText()).isEqualTo("1********** 欢迎来电");

        assertThat(session.flush()).isEmpty();

        // Single chunk full masking test
        StreamingOutputGuardrail.StreamSession session2 = guardrail.newSession();
        StreamingOutputGuardrail.InspectionResult rFull = session2.inspect("手机号: 13912345678");
        assertThat(rFull.sanitizedText()).isEqualTo("手机号: 1**********");
    }

    @Test
    @DisplayName("flush() must release a trailing partial token that never became sensitive")
    void flushShouldReleasePendingTail() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.MASK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        assertThat(session.inspect("订单号 138").sanitizedText()).isEqualTo("订单号 ");
        assertThat(session.flush()).isEqualTo("138");
        assertThat(session.flush()).isEmpty();
    }

    @Test
    @DisplayName("Should mask a full API key split across chunks in MASK mode")
    void shouldMaskCrossChunkApiKey() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.MASK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        assertThat(session.inspect("key: sk-abcdef1234567").sanitizedText()).isEqualTo("key: ");
        String rest = session.inspect("8901234567890 to authenticate.").sanitizedText();
        String tail = session.flush();

        assertThat(rest + tail)
                .isEqualTo("[REDACTED_API_KEY] to authenticate.")
                .doesNotContain("abcdef1234567");
    }

    @Test
    @DisplayName("Every split point of a phone number must produce masked output only")
    void shouldNeverLeakPhoneNumberAtAnySplitPoint() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.MASK);
        String text = "电话" + PHONE + "谢谢";

        for (int split = 0; split <= text.length(); split++) {
            StreamingOutputGuardrail.StreamSession session = guardrail.newSession();
            StringBuilder emitted = new StringBuilder();
            emitted.append(session.inspect(text.substring(0, split)).sanitizedText());
            emitted.append(session.inspect(text.substring(split)).sanitizedText());
            emitted.append(session.flush());

            String output = emitted.toString();
            assertThat(output).as("split at %d must keep the mask", split).contains("1**********");
            for (int i = 0; i + 7 <= PHONE.length(); i++) {
                assertThat(output)
                        .as("split at %d must not leak %s", split, PHONE.substring(i, i + 7))
                        .doesNotContain(PHONE.substring(i, i + 7));
            }
        }
    }

    @Test
    @DisplayName("Character-by-character streaming must never leak a phone number")
    void shouldNeverLeakWhenStreamedCharacterByCharacter() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.MASK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        StringBuilder emitted = new StringBuilder();
        for (char c : ("联系方式" + PHONE).toCharArray()) {
            emitted.append(session.inspect(String.valueOf(c)).sanitizedText());
        }
        emitted.append(session.flush());

        assertThat(emitted.toString()).isEqualTo("联系方式1**********");
    }

    @Test
    @DisplayName("Long digit runs beyond any card length should not be held forever")
    void shouldReleaseLongDigitRuns() {
        StreamingOutputGuardrail guardrail = new StreamingOutputGuardrail(StreamingOutputGuardrail.Mode.MASK);
        StreamingOutputGuardrail.StreamSession session = guardrail.newSession();

        String orderNo = "1234567890123456789012345";
        String emitted = session.inspect("单号 " + orderNo).sanitizedText() + session.flush();
        assertThat(emitted).isEqualTo("单号 " + orderNo);
    }
}
