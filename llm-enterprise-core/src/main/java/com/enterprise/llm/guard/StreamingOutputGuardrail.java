package com.enterprise.llm.guard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise Streaming Output Guardrail with sliding-window cross-chunk inspection.
 * Detects sensitive data (phones, ID cards, bank cards, API keys) spanning multiple token chunks
 * and either blocks immediately or masks sensitive content.
 */
public class StreamingOutputGuardrail {

    public enum Mode {
        BLOCK,
        MASK
    }

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD_PATTERN = Pattern.compile("(?<!\\d)[4-6]\\d{15,18}(?!\\d)");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(?i)(?:sk-[a-zA-Z0-9_-]{20,}|ghp_[a-zA-Z0-9]{20,}|AKIA[0-9A-Z]{16})");

    private final Mode mode;
    private final int overlapBufferSize;

    public StreamingOutputGuardrail(Mode mode, int overlapBufferSize) {
        this.mode = (mode != null) ? mode : Mode.BLOCK;
        this.overlapBufferSize = Math.max(32, overlapBufferSize);
    }

    public StreamingOutputGuardrail(Mode mode) {
        this(mode, 64);
    }

    public StreamingOutputGuardrail() {
        this(Mode.BLOCK, 64);
    }

    /**
     * Creates a new sliding-window session for a single streaming response.
     */
    public StreamSession newSession() {
        return new StreamSession();
    }

    public Mode getMode() {
        return mode;
    }

    public record InspectionResult(
            boolean blocked,
            String violationType,
            String sanitizedText
    ) {
        public static InspectionResult pass(String text) {
            return new InspectionResult(false, null, text);
        }

        public static InspectionResult block(String violationType) {
            return new InspectionResult(true, violationType, null);
        }

        public static InspectionResult mask(String violationType, String maskedText) {
            return new InspectionResult(false, violationType, maskedText);
        }
    }

    /**
     * Per-stream sliding-window session maintaining state across chunks.
     */
    public class StreamSession {

        private final StringBuilder windowBuffer = new StringBuilder();

        public synchronized InspectionResult inspect(String chunkText) {
            if (chunkText == null || chunkText.isEmpty()) {
                return InspectionResult.pass(chunkText);
            }

            int prefixLen = windowBuffer.length();
            windowBuffer.append(chunkText);
            String currentWindow = windowBuffer.toString();

            // 1. Check API Keys
            Matcher keyMatcher = API_KEY_PATTERN.matcher(currentWindow);
            if (keyMatcher.find()) {
                if (mode == Mode.BLOCK) {
                    throw new StreamingGuardrailException("API_KEY", keyMatcher.group());
                }
                currentWindow = keyMatcher.replaceAll("[REDACTED_API_KEY]");
                resetBuffer(currentWindow);
                return InspectionResult.mask("API_KEY", currentWindow.substring(prefixLen));
            }

            // 2. Check Chinese Resident ID Cards
            Matcher idMatcher = ID_CARD_PATTERN.matcher(currentWindow);
            if (idMatcher.find()) {
                if (mode == Mode.BLOCK) {
                    throw new StreamingGuardrailException("ID_CARD", idMatcher.group());
                }
                currentWindow = idMatcher.replaceAll("******************");
                resetBuffer(currentWindow);
                return InspectionResult.mask("ID_CARD", currentWindow.substring(prefixLen));
            }

            // 3. Check Bank Cards
            Matcher bankMatcher = BANK_CARD_PATTERN.matcher(currentWindow);
            if (bankMatcher.find()) {
                if (mode == Mode.BLOCK) {
                    throw new StreamingGuardrailException("BANK_CARD", bankMatcher.group());
                }
                currentWindow = bankMatcher.replaceAll("****************");
                resetBuffer(currentWindow);
                return InspectionResult.mask("BANK_CARD", currentWindow.substring(prefixLen));
            }

            // 4. Check Mobile Phone Numbers
            Matcher phoneMatcher = PHONE_PATTERN.matcher(currentWindow);
            if (phoneMatcher.find()) {
                if (mode == Mode.BLOCK) {
                    throw new StreamingGuardrailException("PHONE_NUMBER", phoneMatcher.group());
                }
                currentWindow = phoneMatcher.replaceAll("1**********");
                resetBuffer(currentWindow);
                return InspectionResult.mask("PHONE_NUMBER", currentWindow.substring(prefixLen));
            }

            // Maintain sliding window buffer size to avoid unbounded memory growth
            trimBuffer();

            return InspectionResult.pass(chunkText);
        }

        private void trimBuffer() {
            if (windowBuffer.length() > overlapBufferSize * 2) {
                windowBuffer.delete(0, windowBuffer.length() - overlapBufferSize);
            }
        }

        private void resetBuffer(String updated) {
            windowBuffer.setLength(0);
            windowBuffer.append(updated);
            trimBuffer();
        }
    }
}
