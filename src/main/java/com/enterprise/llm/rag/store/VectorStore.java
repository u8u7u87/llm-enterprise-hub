package com.enterprise.llm.rag.store;

import com.enterprise.llm.rag.model.DocumentChunk;
import com.enterprise.llm.rag.model.SearchResult;

import java.util.List;

public interface VectorStore {

    /**
     * Insert or update document chunks in the vector store.
     *
     * @param chunks list of document chunks with embeddings
     */
    void insert(List<DocumentChunk> chunks);

    /**
     * Perform similarity search strictly filtered by tenantId.
     *
     * @param tenantId    tenant identifier for strict multi-tenant isolation
     * @param queryVector dense embedding vector of the search query
     * @param topK        maximum number of hits to return
     * @param minScore    minimum similarity score threshold (0.0 - 1.0)
     * @return ranked search results
     */
    List<SearchResult> search(String tenantId, float[] queryVector, int topK, double minScore);

    /**
     * Delete document chunks belonging to a specific document under a tenant.
     *
     * @param tenantId tenant identifier
     * @param docName  document filename or identifier
     */
    void deleteByDocName(String tenantId, String docName);

    /**
     * Delete all records for a tenant.
     *
     * @param tenantId tenant identifier
     */
    void deleteByTenant(String tenantId);
}
