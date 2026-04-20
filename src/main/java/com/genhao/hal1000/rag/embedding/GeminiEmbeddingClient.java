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

    public GeminiEmbeddingClient(
            String baseUrl,
            String apiKey,
            String model,
            int timeoutSeconds,
            int batchSize,
            ObjectMapper objectMapper
    ) {
        var b = baseUrl == null || baseUrl.isBlank() ? "https://generativelanguage.googleapis.com" : baseUrl;
        this.client = WebClient.builder().baseUrl(b).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = normalizeModel(model);
        this.timeoutSeconds = timeoutSeconds;
        this.batchSize = Math.max(1, batchSize);
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
            throw new IllegalStateException("Gemini embeddings failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
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
