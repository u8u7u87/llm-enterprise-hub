package com.enterprise.llm.config;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
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
}
