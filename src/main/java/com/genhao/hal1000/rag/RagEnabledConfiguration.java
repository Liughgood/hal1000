package com.genhao.hal1000.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genhao.hal1000.llm.LlmProperties;
import com.genhao.hal1000.rag.embedding.EmbeddingClient;
import com.genhao.hal1000.rag.embedding.GeminiEmbeddingClient;
import com.genhao.hal1000.rag.embedding.OpenAiEmbeddingClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ConditionalOnProperty(name = "hal1000.rag.enabled", havingValue = "true")
public class RagEnabledConfiguration {

    @Bean
    EmbeddingClient embeddingClient(RagProperties rag, LlmProperties llm) {
        // Local mapper: Spring Boot 4 may not expose ObjectMapper as a bean in this slice; matches LLM gateways.
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var provider = rag.getEmbeddingProvider() == null ? "openai" : rag.getEmbeddingProvider().trim().toLowerCase();
        var base = rag.getEmbeddingBaseUrl();
        if (base == null || base.isBlank()) {
            base = switch (provider) {
                case "gemini" -> (llm.getBaseUrl() != null && !llm.getBaseUrl().isBlank())
                        ? llm.getBaseUrl()
                        : "https://generativelanguage.googleapis.com";
                default -> "https://api.openai.com";
            };
        }
        var key = rag.getEmbeddingApiKey();
        if (key == null || key.isBlank()) {
            key = llm.getApiKey();
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "hal1000.rag.enabled=true requires HAL1000_LLM_API_KEY or HAL1000_RAG_EMBEDDING_API_KEY for embeddings");
        }
        var timeout = llm.getTimeoutSeconds() > 0 ? llm.getTimeoutSeconds() : 60;
        var model = rag.getEmbeddingModel();
        if (model == null || model.isBlank()) {
            model = "gemini".equals(provider) ? "text-embedding-004" : "text-embedding-3-small";
        }
        int maxBytes = Math.max(256_000, rag.getEmbeddingMaxResponseBytes());
        var strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
        var webClient = WebClient.builder()
                .baseUrl(base)
                .exchangeStrategies(strategies)
                .build();
        return switch (provider) {
            case "gemini" -> new GeminiEmbeddingClient(
                    webClient,
                    key,
                    model,
                    timeout,
                    rag.getEmbeddingBatchSize(),
                    rag.getEmbeddingRetryMaxAttempts(),
                    rag.getEmbeddingRetryBaseDelayMs(),
                    rag.getEmbeddingRetryMaxDelayMs(),
                    objectMapper
            );
            default -> new OpenAiEmbeddingClient(
                    webClient,
                    key,
                    model,
                    timeout,
                    rag.getEmbeddingBatchSize(),
                    rag.getEmbeddingRetryMaxAttempts(),
                    rag.getEmbeddingRetryBaseDelayMs(),
                    rag.getEmbeddingRetryMaxDelayMs(),
                    objectMapper
            );
        };
    }
}
