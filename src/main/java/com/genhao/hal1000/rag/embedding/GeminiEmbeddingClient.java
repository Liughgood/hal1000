package com.genhao.hal1000.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Google Generative Language API: {@code :batchEmbedContents} when multiple texts, else {@code :embedContent}.
 */
public class GeminiEmbeddingClient implements EmbeddingClient {

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int batchSize;
    private final int retryMaxAttempts;
    private final int retryBaseDelayMs;
    private final int retryMaxDelayMs;

    public GeminiEmbeddingClient(
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
        this.client = baseClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = normalizeModel(model);
        this.timeoutSeconds = timeoutSeconds;
        this.batchSize = Math.max(1, batchSize);
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBaseDelayMs = Math.max(50, retryBaseDelayMs);
        this.retryMaxDelayMs = Math.max(this.retryBaseDelayMs, retryMaxDelayMs);
    }

    private static String normalizeModel(String model) {
        if (model == null) return "";
        var m = model.trim();
        if (m.startsWith("models/")) {
            return m.substring("models/".length());
        }
        return m;
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
            if (slice.size() == 1) {
                all.add(embedOne(slice.get(0)));
            } else {
                all.addAll(embedBatch(slice));
            }
        }
        return all;
    }

    private float[] embedOne(String text) {
        var body = objectMapper.createObjectNode();
        var content = body.putObject("content");
        var parts = content.putArray("parts");
        parts.addObject().put("text", text);

        String json = post(":embedContent", body);
        return parseSingleEmbedding(json);
    }

    private List<float[]> embedBatch(List<String> slice) {
        var body = objectMapper.createObjectNode();
        ArrayNode requests = body.putArray("requests");
        for (var t : slice) {
            ObjectNode req = requests.addObject();
            req.put("model", "models/" + model);
            var content = req.putObject("content");
            var parts = content.putArray("parts");
            parts.addObject().put("text", t);
        }

        String json = post(":batchEmbedContents", body);
        return parseBatchEmbeddings(json, slice.size());
    }

    private String post(String actionSuffix, ObjectNode body) {
        final String jsonBody;
        try {
            // Must serialize JsonNode to a string; passing ObjectNode to bodyValue() can encode it as a POJO
            // (fields like "array", "bigDecimal") instead of real JSON, which Gemini rejects with 400.
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("serialize Gemini embedding request failed", e);
        }
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            try {
                return client.post()
                        .uri(uriBuilder -> uriBuilder
                                .path("/v1beta/models/{model}" + actionSuffix)
                                .queryParam("key", apiKey)
                                .build(model))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(jsonBody)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .block();
            } catch (WebClientResponseException e) {
                if (!isRetryable(e) || attempt == retryMaxAttempts) {
                    throw new IllegalStateException(
                            "Gemini embeddings failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
                }
                sleepBackoff(attempt, e);
            } catch (RuntimeException e) {
                if (attempt == retryMaxAttempts) {
                    throw new IllegalStateException("Gemini embeddings request failed: " + e.getMessage(), e);
                }
                sleepBackoff(attempt, (WebClientResponseException) null);
            }
        }
        throw new IllegalStateException("Gemini embeddings failed after retries");
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
        // Exponential backoff with jitter: base * 2^(attempt-1) ± 20%
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
            // Retry-After is usually seconds for 429
            long seconds = Long.parseLong(ra.trim());
            return Math.max(0, seconds) * 1000L;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private float[] parseSingleEmbedding(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode values = root.path("embedding").path("values");
            return jsonArrayToFloats(values);
        } catch (Exception e) {
            throw new IllegalStateException("parse Gemini embedContent failed", e);
        }
    }

    private List<float[]> parseBatchEmbeddings(String json, int expected) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode embeddings = root.path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != expected) {
                throw new IllegalStateException("unexpected batch embeddings size");
            }
            var out = new ArrayList<float[]>(expected);
            for (JsonNode emb : embeddings) {
                JsonNode values = emb.path("values");
                if (!values.isArray()) {
                    values = emb.path("embedding").path("values");
                }
                out.add(jsonArrayToFloats(values));
            }
            return out;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("parse Gemini batchEmbedContents failed", e);
        }
    }

    private static float[] jsonArrayToFloats(JsonNode values) {
        if (!values.isArray()) {
            throw new IllegalStateException("missing values array");
        }
        var vec = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vec[i] = (float) values.get(i).asDouble();
        }
        return vec;
    }
}
