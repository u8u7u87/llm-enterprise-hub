package com.enterprise.llm.config;

import com.enterprise.llm.client.LlmClient;
import com.enterprise.llm.client.OpenAiCompatibleLlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LlmClient llmClient(
            @Value("${llm.provider.base-url:http://localhost:8000}") String baseUrl,
            @Value("${llm.provider.api-key:dummy-key}") String apiKey
    ) {
        return new OpenAiCompatibleLlmClient(baseUrl, apiKey);
    }
}
