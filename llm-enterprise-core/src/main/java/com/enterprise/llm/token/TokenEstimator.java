package com.enterprise.llm.token;

import com.enterprise.llm.model.ChatMessage;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TokenEstimator {

    public static final int DEFAULT_COMPLETION_ESTIMATE = 512;

    private final Encoding encoding;

    public TokenEstimator() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    public int estimatePromptTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (ChatMessage msg : messages) {
            // Per OpenAI specification: each message has ~4 structural tokens (role, formatting)
            total += 4;
            if (msg.role() != null) {
                total += countTokens(msg.role());
            }
            if (msg.content() != null) {
                total += countTokens(msg.content());
            }
        }
        total += 2; // priming tokens
        return total;
    }

    public int estimateTotalReservationTokens(List<ChatMessage> messages, Integer maxTokens) {
        int promptTokens = estimatePromptTokens(messages);
        int completionEstimate = (maxTokens != null && maxTokens > 0) ? maxTokens : DEFAULT_COMPLETION_ESTIMATE;
        return promptTokens + completionEstimate;
    }
}
