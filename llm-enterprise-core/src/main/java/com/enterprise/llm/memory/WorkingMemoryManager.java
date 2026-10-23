package com.enterprise.llm.memory;

import com.enterprise.llm.model.ChatCompletionRequest;
import com.enterprise.llm.model.ChatMessage;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages offloading long-form tool and assistant outputs to a temporary working memory cache,
 * and rehydrating them into the context when subsequent turns exhibit high semantic relevance.
 */
@Component
public class WorkingMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryManager.class);
    private static final Pattern MEMORY_REF_PATTERN = Pattern.compile("\\[WorkingMemoryRef:\\s*id=\"([^\"]+)\"\\s*summary=\"([^\"]*)\"\\]");

    // sessionId -> (memoryId -> WorkingMemoryItem)
    private final Map<String, Map<String, WorkingMemoryItem>> sessionMemories = new ConcurrentHashMap<>();
    private final EmbeddingClient embeddingClient;

    @Autowired
    public WorkingMemoryManager(@Autowired(required = false) EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    public WorkingMemoryManager() {
        this(null);
    }

    /**
     * Offloads raw content to working memory cache and returns a lightweight reference stub.
     */
    public String offload(String sessionId, String rawContent, String role, String summaryHint) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        String memId = "mem-" + UUID.randomUUID().toString().substring(0, 8);
        String summary = (summaryHint != null && !summaryHint.isBlank())
                ? summaryHint
                : (rawContent.length() > 60 ? rawContent.substring(0, 60) + "..." : rawContent);

        float[] emb = null;
        if (embeddingClient != null) {
            try {
                emb = embeddingClient.embed(summary);
            } catch (Exception e) {
                log.debug("Failed to calculate embedding for working memory item", e);
            }
        }

        WorkingMemoryItem item = new WorkingMemoryItem(memId, sessionId, summary, rawContent, role, System.currentTimeMillis(), emb);
        sessionMemories.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(memId, item);

        return String.format("[WorkingMemoryRef: id=\"%s\" summary=\"%s\"]", memId, summary);
    }

    public Optional<WorkingMemoryItem> get(String sessionId, String memoryId) {
        if (sessionId == null || memoryId == null) {
            return Optional.empty();
        }
        Map<String, WorkingMemoryItem> map = sessionMemories.get(sessionId);
        if (map == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(map.get(memoryId));
    }

    /**
     * Compute semantic relevance score between user query and memory item.
     * Uses cosine similarity if embeddings are present, otherwise token/word overlap score.
     */
    public double computeRelevance(String query, WorkingMemoryItem item) {
        if (query == null || item == null) {
            return 0.0;
        }

        // Direct mention of memoryId
        if (query.contains(item.memoryId())) {
            return 1.0;
        }

        if (embeddingClient != null && item.embedding() != null && item.embedding().length > 0) {
            try {
                float[] queryEmb = embeddingClient.embed(query);
                return cosineSimilarity(queryEmb, item.embedding());
            } catch (Exception e) {
                log.debug("Fallback to lexical similarity", e);
            }
        }

        // Fallback: Lexical Jaccard/keyword similarity
        return lexicalSimilarity(query, item.summary() + " " + item.rawContent());
    }

    private double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "in", "on", "at", "to", "for", "of", "with", "by", "from",
            "and", "or", "not", "this", "that", "it", "my", "your", "his", "her", "their", "our", "what", "which",
            "who", "whom", "where", "when", "why", "how", "did", "do", "does", "can", "could", "will", "would",
            "shall", "should", "tell", "me", "show", "give", "find", "query", "get", "about"
    );

    private double lexicalSimilarity(String query, String text) {
        Set<String> qTokens = tokenize(query);
        Set<String> tTokens = tokenize(text);
        if (qTokens.isEmpty() || tTokens.isEmpty()) {
            return 0.0;
        }
        long overlap = qTokens.stream().filter(tTokens::contains).count();
        return (double) overlap / qTokens.size();
    }

    private Set<String> tokenize(String input) {
        if (input == null) return Collections.emptySet();
        Set<String> tokens = new HashSet<>();
        for (String word : input.toLowerCase().split("[\\s,;.!?:()\\[\\]{}\"\\n\\r#]+")) {
            if (word.length() >= 2 && !STOPWORDS.contains(word)) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    /**
     * Retrieve relevant working memories for a query.
     */
    public List<WorkingMemoryItem> recallRelevant(String sessionId, String query, int topK, double minRelevance) {
        if (sessionId == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        Map<String, WorkingMemoryItem> map = sessionMemories.get(sessionId);
        if (map == null || map.isEmpty()) {
            return Collections.emptyList();
        }

        return map.values().stream()
                .map(item -> Map.entry(item, computeRelevance(query, item)))
                .filter(e -> e.getValue() >= minRelevance)
                .sorted(Map.Entry.<WorkingMemoryItem, Double>comparingByValue().reversed())
                .limit(topK > 0 ? topK : 3)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Automatically scan the request for dehydration of large messages, or rehydration if query is relevant.
     */
    public ChatCompletionRequest recallAndRehydrate(ChatCompletionRequest request, double minRelevance) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return request;
        }
        String sessionId = request.sessionId() != null ? request.sessionId() : "default";

        // 1. Identify latest user query
        String userQuery = "";
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatMessage m = request.messages().get(i);
            if ("user".equalsIgnoreCase(m.role()) && m.content() != null) {
                userQuery = m.content();
                break;
            }
        }

        if (userQuery.isBlank()) {
            return request;
        }

        // 2. Find relevant memories for this query
        List<WorkingMemoryItem> recalled = recallRelevant(sessionId, userQuery, 3, minRelevance);
        if (recalled.isEmpty()) {
            return request;
        }

        log.info("Working memory recalled {} items for query '{}'", recalled.size(), userQuery);

        // 3. Rehydrate: replace [WorkingMemoryRef: id="mem-xxxx" ...] stubs in conversation with rawContent
        Map<String, String> idToRaw = new HashMap<>();
        for (WorkingMemoryItem item : recalled) {
            idToRaw.put(item.memoryId(), item.rawContent());
        }

        List<ChatMessage> rehydrated = new ArrayList<>();
        boolean changed = false;
        for (ChatMessage msg : request.messages()) {
            if (msg.content() != null && msg.content().contains("[WorkingMemoryRef:")) {
                Matcher matcher = MEMORY_REF_PATTERN.matcher(msg.content());
                StringBuilder sb = new StringBuilder();
                boolean msgChanged = false;
                while (matcher.find()) {
                    String memId = matcher.group(1);
                    if (idToRaw.containsKey(memId)) {
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(idToRaw.get(memId)));
                        msgChanged = true;
                    }
                }
                matcher.appendTail(sb);
                if (msgChanged) {
                    rehydrated.add(new ChatMessage(msg.role(), sb.toString(), msg.toolCalls(), msg.toolCallId()));
                    changed = true;
                    continue;
                }
            }
            rehydrated.add(msg);
        }

        // If the stub was already pruned from history, inject as supplementary contextual memory note
        if (!changed) {
            StringBuilder memNote = new StringBuilder("\n[Contextual Working Memory Recalled]:\n");
            for (WorkingMemoryItem item : recalled) {
                memNote.append("- (From ").append(item.role()).append("): ").append(item.rawContent()).append("\n");
            }
            List<ChatMessage> augmented = new ArrayList<>(request.messages());
            augmented.add(ChatMessage.system(memNote.toString()));
            return new ChatCompletionRequest(
                    request.model(), augmented, request.stream(), request.temperature(),
                    request.maxTokens(), request.tools(), request.toolChoice(), request.sessionId(), request.user()
            );
        }

        return new ChatCompletionRequest(
                request.model(), rehydrated, request.stream(), request.temperature(),
                request.maxTokens(), request.tools(), request.toolChoice(), request.sessionId(), request.user()
        );
    }

    /**
     * Dehydrates oversized tool outputs or messages (> thresholdChars) in request messages.
     */
    public ChatCompletionRequest dehydrate(ChatCompletionRequest request, int thresholdChars) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            return request;
        }
        String sessionId = request.sessionId() != null ? request.sessionId() : "default";

        List<ChatMessage> dehydrated = new ArrayList<>();
        boolean modified = false;
        int size = request.messages().size();

        for (int i = 0; i < size; i++) {
            ChatMessage msg = request.messages().get(i);
            // Don't dehydrate the very last message if it's the current active user prompt
            if (i == size - 1 && "user".equalsIgnoreCase(msg.role())) {
                dehydrated.add(msg);
                continue;
            }

            if (msg.content() != null && msg.content().length() > thresholdChars) {
                String summary = "[Large payload of " + msg.content().length() + " chars from " + msg.role() + "]";
                String refStub = offload(sessionId, msg.content(), msg.role(), summary);
                dehydrated.add(new ChatMessage(msg.role(), refStub, msg.toolCalls(), msg.toolCallId()));
                modified = true;
            } else {
                dehydrated.add(msg);
            }
        }

        if (!modified) {
            return request;
        }

        return new ChatCompletionRequest(
                request.model(), dehydrated, request.stream(), request.temperature(),
                request.maxTokens(), request.tools(), request.toolChoice(), request.sessionId(), request.user()
        );
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            sessionMemories.remove(sessionId);
        } else {
            sessionMemories.clear();
        }
    }
}
