package com.genhao.hal1000.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    @Primary
    LlmGateway llmGateway(LlmProperties props, OpenAiCompatGateway openAi, GeminiAiStudioGateway gemini) {
        if (props.getProvider() == null) {
            return openAi;
        }
        return switch (props.getProvider().trim().toLowerCase()) {
            case "gemini" -> gemini;
            case "openai" -> openAi;
            default -> openAi;
        };
    }
}

