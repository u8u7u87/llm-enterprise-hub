package com.sample.app;

import com.enterprise.llm.starter.EnableLlmEnterprise;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableLlmEnterprise
public class SampleBusinessApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleBusinessApplication.class, args);
    }
}
