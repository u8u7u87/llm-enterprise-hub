package com.enterprise.llm.guard.privacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyVaultTest {

    private PrivacyVault vault;
    private PrivacyMaskingService maskingService;
    private PrivacyDeMaskingTransformer demaskingTransformer;

    @BeforeEach
    void setUp() {
        vault = new PrivacyVault();
        maskingService = new PrivacyMaskingService();
        demaskingTransformer = new PrivacyDeMaskingTransformer();
    }

    @Test
    @DisplayName("Should detect and mask phone, email and ID card into vault placeholders")
    void shouldMaskSensitiveEntities() {
        String input = "用户张三，手机号: 13812345678，邮箱: zhangsan@example.com，身份证: 110101199003072345，请协助查询。";

        String masked = maskingService.maskAndStore(input, vault);

        assertThat(masked).doesNotContain("13812345678");
        assertThat(masked).doesNotContain("zhangsan@example.com");
        assertThat(masked).doesNotContain("110101199003072345");

        assertThat(masked).contains("<PHONE_1>");
        assertThat(masked).contains("<EMAIL_1>");
        assertThat(masked).contains("<ID_CARD_1>");

        assertThat(vault.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("Should transparently restore placeholders back to original values")
    void shouldRestorePlaceholdersTransparently() {
        String input = "查询手机 13812345678 的账单";
        String masked = maskingService.maskAndStore(input, vault);

        // Simulated LLM model response that refers to the placeholder
        String modelResponse = "已成功核验用户，<PHONE_1> 当前账户余额为 128.50 元。";

        String restored = demaskingTransformer.demask(modelResponse, vault);

        assertThat(restored).isEqualTo("已成功核验用户，13812345678 当前账户余额为 128.50 元。");
    }

    @Test
    @DisplayName("Should correctly demask across split streaming chunks")
    void shouldDeMaskAcrossSplitStreamingChunks() {
        String input = "手机 13987654321";
        maskingService.maskAndStore(input, vault); // produces <PHONE_1>

        PrivacyDeMaskingTransformer.StreamDeMasker streamDeMasker = demaskingTransformer.createStreamDeMasker(vault);

        // Simulating LLM SSE chunks split right inside the placeholder: "<PH" and "ONE_1> 验证完成"
        List<String> outputChunks = new ArrayList<>();

        String c1 = streamDeMasker.transformChunk("账户 ");
        if (!c1.isEmpty()) outputChunks.add(c1);

        String c2 = streamDeMasker.transformChunk("<PH");
        if (!c2.isEmpty()) outputChunks.add(c2);

        String c3 = streamDeMasker.transformChunk("ONE_1> 验证完成");
        if (!c3.isEmpty()) outputChunks.add(c3);

        String flushed = streamDeMasker.flush();
        if (!flushed.isEmpty()) outputChunks.add(flushed);

        String fullOutput = String.join("", outputChunks);
        assertThat(fullOutput).isEqualTo("账户 13987654321 验证完成");
    }
}
