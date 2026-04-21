package com.genhao.hal1000.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "hal1000.rag")
public class RagProperties {

    private boolean enabled = false;
    private int topK = 5;
    private int maxPromptChars = 6000;
    private int chunkSizeChars = 1200;
    private int chunkOverlapChars = 200;
    private int maxUrlBytes = 2_000_000;
    private int embeddingBatchSize = 16;
    private int maxExtractChars = 1_000_000;
    private int maxChunks = 2000;
    private int embeddingMaxResponseBytes = 8_000_000;
    private int embeddingRetryMaxAttempts = 6;
    private int embeddingRetryBaseDelayMs = 1000;
    private int embeddingRetryMaxDelayMs = 30000;

    /**
     * openai | gemini — independent of chat LLM provider.
     */
    private String embeddingProvider = "openai";

    /** When blank, startup code picks a default per {@code embedding-provider} (OpenAI vs Gemini). */
    private String embeddingModel = "";

    /**
     * When blank, {@link com.genhao.hal1000.rag.embedding.EmbeddingClientFactory} falls back to {@code hal1000.llm.base-url}.
     */
    private String embeddingBaseUrl = "";

    /**
     * When blank, falls back to {@code hal1000.llm.api-key}.
     */
    private String embeddingApiKey = "";
}
