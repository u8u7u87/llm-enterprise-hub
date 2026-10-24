package com.enterprise.llm.rag.rerank;

import com.enterprise.llm.rag.model.SearchResult;

import java.util.List;

/**
 * Interface for post-retrieval re-ranking and semantic filtering of candidate knowledge chunks.
 */
public interface Reranker {

    /**
     * Re-scores and filters candidate search results.
     *
     * @param query          the search query
     * @param candidateHits  initial candidate chunks retrieved from vector store
     * @param topN           maximum number of results to retain
     * @param threshold      minimum combined score threshold for inclusion
     * @return re-ranked and filtered results
     */
    List<SearchResult> rerank(String query, List<SearchResult> candidateHits, int topN, double threshold);
}
