package com.enterprise.llm.rag.guard;

/**
 * Action taken by the RAG Guardrail based on faithfulness confidence score.
 */
public enum FaithfulnessAction {
    PASS,
    WARN_APPEND,
    REJECT_FALLBACK
}
