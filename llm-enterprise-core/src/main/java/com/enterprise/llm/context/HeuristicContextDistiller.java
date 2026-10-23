package com.enterprise.llm.context;

import com.enterprise.llm.model.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HeuristicContextDistiller implements ContextDistiller {

    @Override
    public String distill(List<ChatMessage> expiredMessages) {
        if (expiredMessages == null || expiredMessages.isEmpty()) {
            return "";
        }

        StringBuilder summary = new StringBuilder("【前序会话历史提炼摘要与背景备忘】\n");
        int count = 0;
        for (ChatMessage msg : expiredMessages) {
            if (msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            String role = msg.role() != null ? msg.role().toUpperCase() : "UNKNOWN";
            String preview = extractKeySentences(msg.content(), 120);
            summary.append("- [").append(role).append("]: ").append(preview).append("\n");
            count++;
            if (count >= 10) {
                summary.append("- ... [更多早期历史已折叠]\n");
                break;
            }
        }
        return summary.toString().trim();
    }

    private String extractKeySentences(String text, int maxChars) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, maxChars) + "...";
    }
}
