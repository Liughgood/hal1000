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
import java.util.concurrent.ThreadLocalRandom;

public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int timeoutSeconds;
    private final int batchSize;
    private final int retryMaxAttempts;
    private final int retryBaseDelayMs;
    private final int retryMaxDelayMs;

    public OpenAiEmbeddingClient(
            WebClient baseClient,
            String apiKey,
            String model,
            int timeoutSeconds,
            int batchSize,
            int retryMaxAttempts,
            int retryBaseDelayMs,
            int retryMaxDelayMs,
            ObjectMapper objectMapper
    ) {
        this.objectMapper = objectMapper;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.batchSize = Math.max(1, batchSize);
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBaseDelayMs = Math.max(50, retryBaseDelayMs);
        this.retryMaxDelayMs = Math.max(this.retryBaseDelayMs, retryMaxDelayMs);
        var b = baseClient.mutate()
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
        json = postWithRetry(jsonBody);

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

    private String postWithRetry(String jsonBody) {
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return client.post()
                        .uri("/v1/embeddings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(jsonBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .block();
            } catch (WebClientResponseException e) {
                if (!isRetryable(e) || attempt == retryMaxAttempts) {
                    throw new IllegalStateException("OpenAI embeddings failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
                }
                sleepBackoff(attempt, e);
            } catch (RuntimeException e) {
                if (attempt == retryMaxAttempts) {
                    throw new IllegalStateException("OpenAI embeddings request failed: " + e.getMessage(), e);
                }
                sleepBackoff(attempt, null);
            }
        }
        throw new IllegalStateException("OpenAI embeddings failed after retries");
    }

    private boolean isRetryable(WebClientResponseException e) {
        int code = e.getStatusCode().value();
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private void sleepBackoff(int attempt, WebClientResponseException e) {
        long delayMs = computeDelayMs(attempt, e);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off for embeddings retry", ie);
        }
    }

    private long computeDelayMs(int attempt, WebClientResponseException e) {
        Long retryAfterMs = parseRetryAfterMs(e);
        if (retryAfterMs != null && retryAfterMs > 0) {
            return Math.min(retryAfterMs, retryMaxDelayMs);
        }
        long exp = (long) retryBaseDelayMs << Math.min(20, Math.max(0, attempt - 1));
        long capped = Math.min(exp, (long) retryMaxDelayMs);
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Math.max(50, (long) (capped * jitter));
    }

    private static Long parseRetryAfterMs(WebClientResponseException e) {
        if (e == null) return null;
        var ra = e.getHeaders().getFirst("Retry-After");
        if (ra == null || ra.isBlank()) return null;
        try {
            long seconds = Long.parseLong(ra.trim());
            return Math.max(0, seconds) * 1000L;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
