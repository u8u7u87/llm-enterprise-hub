package com.enterprise.llm.rag.embedding;

import java.util.List;

@FunctionalInterface
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
    default List<float[]> embedBatch(List<String> texts) {
        if (texts == null) {
            return List.of();
        }
        return texts.stream().map(this::embed).toList();
    }
}

