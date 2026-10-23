package com.enterprise.llm.rag.embedding;

import java.util.List;

public interface EmbeddingClient {

    /**
     * Generate dense embeddings for a single text input.
     *
     * @param text input text
     * @return dense embedding vector
     */
    float[] embed(String text);

    /**
     * Generate dense embeddings for batch texts.
     *
     * @param texts list of input texts
     * @return list of dense embedding vectors
     */
    List<float[]> embedBatch(List<String> texts);
}
