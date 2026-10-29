package com.enterprise.llm.guard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise streaming output guardrail with cross-chunk inspection.
 *
 * <p><b>Emission semantics.</b> {@link StreamSession#inspect(String)} returns the <em>prefix of the
 * stream that is safe to send to the client right now</em> (possibly the empty string). Any tail that
 * could still turn out to be the beginning of a sensitive token &mdash; a trailing digit run, or an
 * unterminated {@code sk-} / {@code ghp_} / {@code AKIA} token &mdash; is held back until a later
 * chunk resolves it. {@link StreamSession#flush()} releases whatever is still buffered when the
 * upstream stream ends.
 *
 * <p><b>Why hold-back instead of a sliding window.</b> A sliding window that re-emits
 * {@code window.substring(prefixLen)} is fundamentally unsafe: once a chunk has been written to the
 * socket it cannot be retracted. If {@code 1381234} goes out and the next chunk turns it into
 * {@code 13812345678}, the client already holds seven real digits. Holding back the ambiguous tail
 * means masking always happens <em>before</em> the split, so a masked value can never be cut in half
 * and re-emitted in clear text. Ordinary text incurs no delay at all.
 *
 * <p>In {@link Mode#BLOCK} the same hold-back applies, so a blocked stream never leaks the digits it
 * was blocked on.
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

    /**
     * A trailing digit run shorter than this may still grow into an ID card (18) or bank card (19),
     * so it is held back. A run at or beyond this length can no longer match any digit pattern
     * (all of them are anchored by {@code (?<!\d)} / {@code (?!\d)}) and is safe to release.
     */
    private static final int MAX_DIGIT_TOKEN_LENGTH = 19;

    /**
     * A trailing token that starts like an API key but has not reached the minimum matched length
     * yet. The upper bounds are one short of each pattern's minimum, so anything that already
     * qualifies has been masked and no longer matches.
     */
    private static final Pattern OPEN_API_KEY_TAIL = Pattern.compile(
            "(?<![A-Za-z0-9_-])(?:sk-[A-Za-z0-9_-]{0,19}|ghp_[A-Za-z0-9]{0,19}|AKIA[0-9A-Z]{0,15})$");

    private static final String PHONE_MASK = "1**********";
    private static final String ID_CARD_MASK = "******************";
    private static final String BANK_CARD_MASK = "****************";
    private static final String API_KEY_MASK = "[REDACTED_API_KEY]";

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
     * Creates a new session for a single streaming response.
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
     * Per-stream session maintaining state across chunks.
     */
    public class StreamSession {

        /** Retained tail: already masked in MASK mode, still in clear text in BLOCK mode. */
        private final StringBuilder buffer = new StringBuilder();

        /**
         * Feeds one upstream chunk and returns the text that may be emitted right now.
         *
         * @return a result whose {@code sanitizedText} is the safe prefix (never {@code null},
         *         possibly empty). In {@link Mode#BLOCK} a detected violation throws before
         *         anything from the offending region has been released.
         */
        public synchronized InspectionResult inspect(String chunkText) {
            if (chunkText == null || chunkText.isEmpty()) {
                return InspectionResult.pass("");
            }

            buffer.append(chunkText);
            String window = buffer.toString();

            if (mode == Mode.BLOCK) {
                String violation = detectViolation(window);
                if (violation != null) {
                    throw new StreamingGuardrailException(violation, snippetOf(window, violation));
                }
                int holdStart = computeHoldStart(window);
                String emit = window.substring(0, holdStart);
                resetBuffer(window.substring(holdStart));
                return InspectionResult.pass(emit);
            }

            MaskOutcome outcome = maskAll(window);
            int holdStart = computeHoldStart(outcome.text());
            String emit = outcome.text().substring(0, holdStart);
            resetBuffer(outcome.text().substring(holdStart));
            return (outcome.violationType() == null)
                    ? InspectionResult.pass(emit)
                    : InspectionResult.mask(outcome.violationType(), emit);
        }

        /**
         * Releases the text still held back. Must be called once, when the upstream stream ends and
         * before the terminal {@code [DONE]} event is written.
         */
        public synchronized String flush() {
            String tail = buffer.toString();
            resetBuffer("");
            return tail;
        }

        private void resetBuffer(String retained) {
            buffer.setLength(0);
            buffer.append(retained);
        }
    }

    // ---------------------------------------------------------------- masking

    private record MaskOutcome(String text, String violationType) {
    }

    private static MaskOutcome maskAll(String window) {
        String current = window;
        String type = null;

        Matcher keyMatcher = API_KEY_PATTERN.matcher(current);
        if (keyMatcher.find()) {
            type = "API_KEY";
            current = keyMatcher.replaceAll(API_KEY_MASK);
        }

        Matcher idMatcher = ID_CARD_PATTERN.matcher(current);
        if (idMatcher.find()) {
            type = (type != null) ? type : "ID_CARD";
            current = idMatcher.replaceAll(ID_CARD_MASK);
        }

        Matcher bankMatcher = BANK_CARD_PATTERN.matcher(current);
        if (bankMatcher.find()) {
            type = (type != null) ? type : "BANK_CARD";
            current = bankMatcher.replaceAll(BANK_CARD_MASK);
        }

        Matcher phoneMatcher = PHONE_PATTERN.matcher(current);
        if (phoneMatcher.find()) {
            type = (type != null) ? type : "PHONE_NUMBER";
            current = phoneMatcher.replaceAll(PHONE_MASK);
        }

        return new MaskOutcome(current, type);
    }

    private static String detectViolation(String window) {
        if (API_KEY_PATTERN.matcher(window).find()) {
            return "API_KEY";
        }
        if (ID_CARD_PATTERN.matcher(window).find()) {
            return "ID_CARD";
        }
        if (BANK_CARD_PATTERN.matcher(window).find()) {
            return "BANK_CARD";
        }
        if (PHONE_PATTERN.matcher(window).find()) {
            return "PHONE_NUMBER";
        }
        return null;
    }

    private static String snippetOf(String window, String violationType) {
        Pattern pattern = switch (violationType) {
            case "API_KEY" -> API_KEY_PATTERN;
            case "ID_CARD" -> ID_CARD_PATTERN;
            case "BANK_CARD" -> BANK_CARD_PATTERN;
            default -> PHONE_PATTERN;
        };
        Matcher matcher = pattern.matcher(window);
        return matcher.find() ? matcher.group() : "";
    }

    // ------------------------------------------------------------ hold-back

    /**
     * Index from which the text must be withheld: everything at or after it might still become part
     * of a sensitive token once more chunks arrive.
     */
    private int computeHoldStart(String text) {
        int n = text.length();
        int hold = n;

        Matcher keyMatcher = OPEN_API_KEY_TAIL.matcher(text);
        if (keyMatcher.find()) {
            hold = Math.min(hold, keyMatcher.start());
        }

        hold = Math.min(hold, digitRunStart(text));

        // Never withhold more than the configured overlap window, otherwise a pathological input
        // (a very long open token) would stall the stream indefinitely.
        if (n - hold > overlapBufferSize) {
            hold = n - overlapBufferSize;
        }
        return Math.max(0, Math.min(hold, n));
    }

    /**
     * Returns the start index of a trailing digit run that is still short enough to grow into an ID
     * card or bank card, or {@code text.length()} when the tail is not ambiguous.
     */
    private static int digitRunStart(String text) {
        int n = text.length();
        int start = n;
        while (start > 0 && isAsciiDigit(text.charAt(start - 1))) {
            start--;
        }
        int runLength = n - start;
        if (runLength == 0 || runLength >= MAX_DIGIT_TOKEN_LENGTH) {
            return n;
        }
        return start;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
