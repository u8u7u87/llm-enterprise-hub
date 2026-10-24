package com.enterprise.llm.guard.privacy;

import org.springframework.stereotype.Service;

/**
 * 出站反脱敏还原转换器
 * 支持完整字符串还原与流式 SSE Token 跨 Chunk 占位符缓存还原。
 */
@Service
public class PrivacyDeMaskingTransformer {

    public String demask(String text, PrivacyVault vault) {
        if (text == null || text.isBlank() || vault == null || vault.isEmpty()) {
            return text;
        }
        return vault.restore(text);
    }

    public StreamDeMasker createStreamDeMasker(PrivacyVault vault) {
        return new StreamDeMasker(vault);
    }

    /**
     * 流式 SSE 反脱敏缓冲区
     */
    public static class StreamDeMasker {
        private final PrivacyVault vault;
        private final StringBuilder buffer = new StringBuilder();
        private static final int MAX_TAG_LENGTH = 32;

        public StreamDeMasker(PrivacyVault vault) {
            this.vault = vault;
        }

        public synchronized String transformChunk(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return "";
            }
            if (vault == null || vault.isEmpty()) {
                return chunk;
            }

            buffer.append(chunk);

            // 尝试对完整占位符进行还原
            String current = vault.restore(buffer.toString());
            buffer.setLength(0);
            buffer.append(current);

            // 检查末尾是否存在未闭合的占位符片段 (如以 '<' 开头且尚未遇到 '>')
            int lastOpenAngle = buffer.lastIndexOf("<");
            int lastCloseAngle = buffer.lastIndexOf(">");

            if (lastOpenAngle != -1 && lastOpenAngle > lastCloseAngle) {
                // 有未闭合的 tag 片段
                int unclosedLen = buffer.length() - lastOpenAngle;
                if (unclosedLen <= MAX_TAG_LENGTH) {
                    // 保留未闭合部分，冲刷前面的安全文本
                    String emit = buffer.substring(0, lastOpenAngle);
                    String pending = buffer.substring(lastOpenAngle);
                    buffer.setLength(0);
                    buffer.append(pending);
                    return emit;
                }
            }

            // 没有未闭合片段，全部冲刷
            String emit = buffer.toString();
            buffer.setLength(0);
            return emit;
        }

        public synchronized String flush() {
            if (buffer.length() == 0) {
                return "";
            }
            String finalOutput = vault != null ? vault.restore(buffer.toString()) : buffer.toString();
            buffer.setLength(0);
            return finalOutput;
        }
    }
}
