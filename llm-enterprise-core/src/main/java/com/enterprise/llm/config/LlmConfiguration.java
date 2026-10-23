package com.enterprise.llm.config;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import com.enterprise.llm.rag.embedding.EmbeddingClient;
import com.enterprise.llm.rag.embedding.OpenAiCompatibleEmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfiguration {

    @Bean(name = "primaryLlmClient")
    @ConditionalOnMissingBean(name = "primaryLlmClient")
    public LlmClient primaryLlmClient(
            @Value("${llm.provider.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${llm.provider.api-key:ollama}") String apiKey
    ) {
        return new OpenAiCompatibleLlmClient(baseUrl, apiKey);
    }

    @Bean(name = "fallbackLlmClient")
    @ConditionalOnMissingBean(name = "fallbackLlmClient")
    public LlmClient fallbackLlmClient(
            @Value("${llm.fallback.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${llm.fallback.api-key:ollama}") String apiKey
    ) {
        return new OpenAiCompatibleLlmClient(baseUrl, apiKey);
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    public EmbeddingClient embeddingClient(
            @Value("${rag.embedding.base-url:http://localhost:11434/v1}") String baseUrl,
            @Value("${rag.embedding.api-key:ollama}") String apiKey,
            @Value("${rag.embedding.model:bge-m3}") String model
    ) {
        return new OpenAiCompatibleEmbeddingClient(baseUrl, apiKey, model);
    }
}
