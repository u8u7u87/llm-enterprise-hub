package com.enterprise.llm.rag.query;

import com.enterprise.llm.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic Contextual Query Rewriter for resolving anaphoric references
 * and missing subjects in multi-turn dialogues without requiring an extra LLM roundtrip.
 */
public class ContextualQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(ContextualQueryRewriter.class);

    private static final Pattern ANAPHORA_PATTERN = Pattern.compile(
            "(?i)(^|\\s+|[，。！？,\\.!?])(它|它们|他|他们|她|她们|这|这个|这些|那|那个|那些|其|该|此|前者|后者|具体怎么|如何申请|需要多久|需要多少|有什么条件|怎么办理|为什么|还有呢|it|its|this|that|these|those|they|them|how to apply|how much)($|\\s+|[，。！？,\\.!?])"
    );

    private static final Pattern QUOTED_ENTITY_PATTERN = Pattern.compile(
            "[《“\"'「]([^》”\"'」]+)[》”\"'」]"
    );

    @Override
    public String rewriteQuery(List<ChatMessage> conversationHistory, String latestQuery) {
        if (latestQuery == null || latestQuery.isBlank()) {
            return "";
        }
        String trimmedQuery = latestQuery.trim();

        if (conversationHistory == null || conversationHistory.size() <= 1) {
            return trimmedQuery;
        }

        // Determine if query requires disambiguation:
        // 1. Matches common pronouns / anaphora
        // 2. Or is a short follow-up query (e.g. "如何申请？", "需要什么材料？")
        boolean hasAnaphora = ANAPHORA_PATTERN.matcher(trimmedQuery).find();
        boolean isShortFollowUp = trimmedQuery.length() <= 12 && (
                trimmedQuery.endsWith("吗") || trimmedQuery.endsWith("吗？") || trimmedQuery.endsWith("吗?")
                        || trimmedQuery.contains("怎么") || trimmedQuery.contains("如何")
                        || trimmedQuery.contains("什么") || trimmedQuery.contains("多少")
                        || trimmedQuery.contains("多久") || trimmedQuery.contains("条件")
        );

        if (!hasAnaphora && !isShortFollowUp) {
            return trimmedQuery;
        }

        // Extract topic entity from previous conversation turns
        String topicEntity = extractTopicEntity(conversationHistory);
        if (topicEntity == null || topicEntity.isBlank()) {
            return trimmedQuery;
        }

        // Avoid duplicate prefix if query already contains the entity or significant overlap
        if (trimmedQuery.contains(topicEntity)) {
            return trimmedQuery;
        }
        for (String word : topicEntity.split("[\\s,，。！？!?]+")) {
            if (word.length() >= 3 && trimmedQuery.contains(word)) {
                return trimmedQuery;
            }
        }

        String rewritten = "[" + topicEntity + "] " + trimmedQuery;
        log.info("RAG Query Rewriter disambiguated: '{}' -> '{}'", trimmedQuery, rewritten);
        return rewritten;
    }

    private String extractTopicEntity(List<ChatMessage> history) {
        // Iterate backwards from the message immediately before the latest query
        int lastIndex = history.size() - 1;
        // If the last message in history is the latestQuery itself, exclude it
        if (lastIndex >= 0 && "user".equalsIgnoreCase(history.get(lastIndex).role())) {
            lastIndex--;
        }

        for (int i = lastIndex; i >= 0 && i >= lastIndex - 4; i--) {
            ChatMessage msg = history.get(i);
            if (msg.content() == null || msg.content().isBlank()) {
                continue;
            }

            // 1. Check for quoted entities e.g. 《企业年金管理规定》, "商业补充医疗"
            Matcher matcher = QUOTED_ENTITY_PATTERN.matcher(msg.content());
            if (matcher.find()) {
                String candidate = matcher.group(1).trim();
                if (candidate.length() >= 2 && candidate.length() <= 30) {
                    return candidate;
                }
            }

            // 2. If it's a user turn, look for salient noun phrases
            if ("user".equalsIgnoreCase(msg.role())) {
                String cleanContent = msg.content()
                        .replaceAll("^(请问|请介绍一下|我想了解|关于|咨询一下|查一下)", "")
                        .replaceAll("(怎么申请|如何申请|怎么办理|怎么回事|是什么|怎么办|包括什么|有哪些|怎么样|需要满足|需要什么|政策|规定|详情|情况|规则|制度).*$", "")
                        .trim();
                if (cleanContent.length() >= 2 && cleanContent.length() <= 25) {
                    return cleanContent;
                }
            }
        }

        return null;
    }
}
