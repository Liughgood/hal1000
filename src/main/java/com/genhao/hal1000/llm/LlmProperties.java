package com.genhao.hal1000.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hal1000.llm")
public class LlmProperties {

    /**
     * Supported: openai, gemini
     */
    private String provider = "openai";
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 60;
}

