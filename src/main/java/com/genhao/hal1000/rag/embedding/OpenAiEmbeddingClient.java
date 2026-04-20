package com.genhao.hal1000.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int timeoutSeconds;
    private final int batchSize;

    public OpenAiEmbeddingClient(
            String baseUrl,
            String apiKey,
            String model,
            int timeoutSeconds,
            int batchSize,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.batchSize = Math.max(1, batchSize);
        var b = WebClient.builder()
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (apiKey != null && !apiKey.isBlank()) {
            b = b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.client = b.build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        var all = new ArrayList<float[]>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(texts.size(), from + batchSize);
            var slice = texts.subList(from, to);
            all.addAll(embedBatch(slice));
        }
        return all;
    }

    private List<float[]> embedBatch(List<String> slice) {
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        var arr = body.putArray("input");
        for (var t : slice) {
            arr.add(t);
        }

        final String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("serialize OpenAI embeddings request failed", e);
        }
        String json;
        try {
            json = client.post()
                    .uri("/v1/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("OpenAI embeddings failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("OpenAI embeddings request failed: " + e.getMessage(), e);
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != slice.size()) {
                throw new IllegalStateException("unexpected embeddings response shape");
            }
            var rows = new ArrayList<JsonNode>();
            data.forEach(rows::add);
            rows.sort(Comparator.comparingInt(n -> n.path("index").asInt(-1)));
            var vectors = new ArrayList<float[]>(slice.size());
            for (JsonNode row : rows) {
                JsonNode emb = row.path("embedding");
                if (!emb.isArray()) {
                    throw new IllegalStateException("missing embedding array");
                }
                var vec = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    vec[i] = (float) emb.get(i).asDouble();
                }
                vectors.add(vec);
            }
            return vectors;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("parse embeddings failed", e);
        }
    }
}
