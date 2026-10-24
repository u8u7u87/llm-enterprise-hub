package com.enterprise.llm.rag.rerank;

import com.enterprise.llm.rag.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hybrid Search Reranker that combines dense vector similarity with sparse lexical matching
 * and exact code/entity boosts to eliminate low-quality chunks and context noise.
 */
public class HybridSearchReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchReranker.class);

    private static final Pattern CODE_ENTITY_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9_-]{3,}\\b|[《“\"'「]([^》”\"'」]+)[》”\"'」]"
    );

    private final double vectorWeight;
    private final double lexicalWeight;

    public HybridSearchReranker(double vectorWeight, double lexicalWeight) {
        double total = vectorWeight + lexicalWeight;
        this.vectorWeight = total > 0 ? vectorWeight / total : 0.7;
        this.lexicalWeight = total > 0 ? lexicalWeight / total : 0.3;
    }

    public HybridSearchReranker() {
        this(0.65, 0.35);
    }

    @Override
    public List<SearchResult> rerank(String query, List<SearchResult> candidateHits, int topN, double threshold) {
        if (candidateHits == null || candidateHits.isEmpty()) {
            return Collections.emptyList();
        }

        if (query == null || query.isBlank()) {
            return candidateHits.stream()
                    .filter(h -> h.score() >= threshold)
                    .limit(topN)
                    .collect(Collectors.toList());
        }

        Set<String> queryTokens = tokenize(query);
        List<String> keyEntities = extractKeyEntities(query);

        List<ScoredHit> scoredHits = new ArrayList<>();

        for (SearchResult hit : candidateHits) {
            double denseScore = Math.max(0.0, Math.min(1.0, hit.score()));
            double lexicalScore = calculateLexicalScore(hit.text(), queryTokens);

            // Exact entity/code match bonus (e.g. specific error code or product model)
            double entityBoost = 0.0;
            if (!keyEntities.isEmpty() && hit.text() != null) {
                for (String entity : keyEntities) {
                    if (hit.text().contains(entity)) {
                        entityBoost += 0.15;
                    }
                }
            }
            entityBoost = Math.min(0.3, entityBoost);

            double effectiveLexical = lexicalScore > 0 ? lexicalScore : denseScore;
            double finalScore = (vectorWeight * denseScore) + (lexicalWeight * effectiveLexical) + entityBoost;
            finalScore = Math.min(1.0, finalScore);

            if (finalScore >= threshold) {
                scoredHits.add(new ScoredHit(hit, finalScore));
            }
        }

        scoredHits.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));

        return scoredHits.stream()
                .limit(topN)
                .map(sh -> sh.hit.withScore(sh.finalScore))
                .collect(Collectors.toList());
    }

    private double calculateLexicalScore(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank() || queryTokens.isEmpty()) {
            return 0.0;
        }

        String lowerText = text.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String token : queryTokens) {
            if (lowerText.contains(token)) {
                matches++;
            }
        }

        return (double) matches / queryTokens.size();
    }

    private Set<String> tokenize(String query) {
        Set<String> tokens = new HashSet<>();
        // Split by whitespace and common punctuation
        String[] words = query.toLowerCase(Locale.ROOT).split("[\\s,，。！？!?:;；]+");
        for (String w : words) {
            String trimmed = w.trim();
            if (trimmed.length() >= 2) {
                tokens.add(trimmed);
            }
        }
        // Chinese 2-character bi-grams
        String cleanChinese = query.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < cleanChinese.length() - 1; i++) {
            tokens.add(cleanChinese.substring(i, i + 2));
        }
        return tokens;
    }

    private List<String> extractKeyEntities(String query) {
        List<String> entities = new ArrayList<>();
        Matcher matcher = CODE_ENTITY_PATTERN.matcher(query);
        while (matcher.find()) {
            String entity = matcher.group(1) != null ? matcher.group(1) : matcher.group();
            if (entity.length() >= 3) {
                entities.add(entity);
            }
        }
        return entities;
    }

    private record ScoredHit(SearchResult hit, double finalScore) {}
}
