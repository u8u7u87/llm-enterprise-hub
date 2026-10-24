package com.enterprise.llm.rag.query;

import com.enterprise.llm.model.ChatMessage;

import java.util.List;

/**
 * Interface for rewriting user queries in multi-turn conversations to produce
 * self-contained, disambiguated search queries for RAG vector retrieval.
 */
public interface QueryRewriter {

    /**
     * Contextualizes the latest user query using preceding conversation history.
     *
     * @param conversationHistory previous conversation turns
     * @param latestQuery         the raw query input by the user in the current turn
     * @return a self-contained, search-ready query string
     */
    String rewriteQuery(List<ChatMessage> conversationHistory, String latestQuery);
}
