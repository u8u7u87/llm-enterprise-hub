package com.enterprise.llm.controller;

import com.enterprise.llm.metrics.LlmMetricsCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:sqlite:target/prometheus-test.db",
        "milvus.endpoint=disabled",
        "management.endpoints.web.exposure.include=*"
})
class PrometheusActuatorEndpointTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired(required = false)
    private LlmMetricsCollector metricsCollector;

    @Test
    @DisplayName("Verify /actuator/prometheus exposes Spring and LLM enterprise metrics")
    void testPrometheusEndpointExposesMetrics() {
        assertThat(metricsCollector).isNotNull();

        // 1. Record an LLM inference metric
        metricsCollector.recordRequest(
                "gpt-4o-mini",
                "tenant-prometheus-e2e",
                "success",
                350,
                120L,
                45.0,
                80,
                160
        );

        // 2. Query /actuator/prometheus
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        String body = response.getBody();

        // 3. Verify Prometheus format metrics
        assertThat(body).contains("llm_requests_total");
        assertThat(body).contains("tenant-prometheus-e2e");
        assertThat(body).contains("gpt-4o-mini");
        assertThat(body).contains("llm_tokens_total");
    }
}
