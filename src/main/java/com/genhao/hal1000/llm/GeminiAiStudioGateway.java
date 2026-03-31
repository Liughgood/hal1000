package com.genhao.hal1000.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gemini (Google AI Studio) integration via Generative Language API.
 * streamGenerateContent: emits partial outputs progressively.
 */
@Component
public class GeminiAiStudioGateway implements LlmGateway {

    private final LlmProperties props;
    private final WebClient client;
    private final ObjectMapper objectMapper;

    public GeminiAiStudioGateway(LlmProperties props) {
        this.props = props;
        var base = props.getBaseUrl();
        if (base == null || base.isBlank()) {
            base = "https://generativelanguage.googleapis.com";
        }
        this.client = WebClient.builder()
                .baseUrl(base)
                .build();
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public Flux<StreamEvent> streamReply(List<SimpleMessage> messages) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            return Flux.error(new IllegalArgumentException("Gemini API Key 未配置：请设置环境变量 HAL1000_LLM_API_KEY"));
        }

        var req = GeminiGenerateContentRequest.from(messages);
        var lastFullText = new AtomicReference<>("");

        return client.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/{model}:streamGenerateContent")
                        .queryParam("key", props.getApiKey())
                        .build(normalizeModel(props.getModel())))
                .contentType(MediaType.APPLICATION_JSON)
                // Gemini streaming may come back as SSE-like "data: {...}" or newline-delimited JSON.
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_NDJSON, MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .transform(GeminiAiStudioGateway::splitStreamingPayloads)
                .flatMap(payload -> {
                    try {
                        var node = objectMapper.readTree(payload);
                        var full = extractText(node);
                        if (full == null || full.isBlank()) {
                            return Flux.empty();
                        }
                        var prev = lastFullText.get();
                        String delta;
                        if (full.startsWith(prev)) {
                            delta = full.substring(prev.length());
                        } else {
                            // Some responses may send only deltas or reset; fall back to emitting full.
                            delta = full;
                        }
                        lastFullText.set(full);
                        if (delta.isBlank()) return Flux.empty();
                        return Flux.just(new StreamEvent(delta));
                    } catch (Exception e) {
                        return Flux.empty();
                    }
                })
                .onErrorMap(WebClientResponseException.class, e ->
                        // preserve status + body for friendly error
                        new WebClientResponseException(
                                e.getStatusCode().value(),
                                e.getStatusText(),
                                e.getHeaders(),
                                e.getResponseBodyAsByteArray(),
                                null
                        )
                );
    }

    private static String normalizeModel(String model) {
        if (model == null) return "";
        var m = model.trim();
        if (m.startsWith("models/")) {
            return m.substring("models/".length());
        }
        return m;
    }

    /**
     * Split a streamed HTTP body (String chunks) into JSON payload strings.
     * Supports:
     * - SSE-like: "data: {json}\n\n"
     * - NDJSON: "{json}\n{json}\n"
     * - Arbitrary chunking: extract JSON objects by brace-depth (robust)
     */
    static Flux<String> splitStreamingPayloads(Flux<String> inbound) {
        return Flux.create(sink -> {
            final StringBuilder buf = new StringBuilder();
            inbound.subscribe(
                    next -> {
                        buf.append(next);
                        drainJsonObjects(buf, sink);
                    },
                    sink::error,
                    () -> {
                        drainJsonObjects(buf, sink);
                        sink.complete();
                    }
            );
        });
    }

    private static void drainJsonObjects(StringBuilder buf, reactor.core.publisher.FluxSink<String> sink) {
        // Strip SSE "data:" prefixes and whitespace that can appear between objects
        // without disturbing JSON structure once we are inside an object.
        int i = 0;
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;

        while (i < buf.length()) {
            char c = buf.charAt(i);

            if (start < 0) {
                // Skip leading whitespace and "data:" tokens outside JSON
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (matchesAt(buf, i, "data:")) {
                    // remove "data:" and following optional space
                    int end = i + 5;
                    buf.delete(i, end);
                    while (i < buf.length() && buf.charAt(i) == ' ') {
                        buf.deleteCharAt(i);
                    }
                    continue;
                }
                if (c == '{') {
                    start = i;
                    depth = 1;
                    i++;
                    continue;
                }
                // Some servers may wrap in an array; ignore [,], commas outside objects
                if (c == '[' || c == ']' || c == ',') {
                    i++;
                    continue;
                }
                // Unknown prefix: drop one char to avoid infinite buffer growth
                buf.deleteCharAt(i);
                continue;
            }

            // We are inside a JSON object: track strings and brace depth
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '\"') {
                    inString = false;
                }
            } else {
                if (c == '\"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        int endExclusive = i + 1;
                        var json = buf.substring(start, endExclusive);
                        sink.next(json);
                        buf.delete(0, endExclusive);
                        // reset scan
                        i = 0;
                        start = -1;
                        inString = false;
                        escape = false;
                        continue;
                    }
                }
            }
            i++;
        }
    }

    private static boolean matchesAt(StringBuilder buf, int idx, String s) {
        if (idx + s.length() > buf.length()) return false;
        for (int j = 0; j < s.length(); j++) {
            if (buf.charAt(idx + j) != s.charAt(j)) return false;
        }
        return true;
    }

    private static String extractJsonFromSseEvent(String evt) {
        var lines = evt.split("\n");
        var dataLines = new ArrayList<String>();
        for (var line : lines) {
            var t = line.trim();
            if (t.startsWith("data:")) {
                dataLines.add(t.substring("data:".length()).trim());
            }
        }
        if (!dataLines.isEmpty()) {
            return String.join("\n", dataLines).trim();
        }
        var t = evt.trim();
        if (t.startsWith("{") && t.endsWith("}")) return t;
        return null;
    }

    private static String extractText(JsonNode node) {
        // candidates[0].content.parts[*].text concatenated
        var candidates = node.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return "";
        var content = candidates.get(0).path("content");
        var parts = content.path("parts");
        if (!parts.isArray() || parts.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var p : parts) {
            var t = p.path("text").asText("");
            if (!t.isEmpty()) sb.append(t);
        }
        return sb.toString();
    }

    // --- DTOs (minimal) ---

    public record GeminiGenerateContentRequest(List<Content> contents) {
        static GeminiGenerateContentRequest from(List<SimpleMessage> messages) {
            // Gemini expects: contents[].role in {"user","model"}; system can be injected as first user instruction for MVP.
            var contents = new ArrayList<Content>();
            for (var m : messages) {
                var role = switch (m.role()) {
                    case "assistant", "model" -> "model";
                    default -> "user";
                };
                contents.add(new Content(role, List.of(new Part(m.content()))));
            }
            return new GeminiGenerateContentRequest(contents);
        }
    }

    public record Content(String role, List<Part> parts) {
    }

    public record Part(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeminiGenerateContentResponse(List<Candidate> candidates) {
        String firstText() {
            if (candidates == null || candidates.isEmpty()) return "";
            var c0 = candidates.get(0);
            if (c0 == null || c0.content == null || c0.content.parts == null || c0.content.parts.isEmpty()) return "";
            var p0 = c0.content.parts.get(0);
            return p0 == null ? "" : p0.text;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Candidate {
        public ContentOut content;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentOut {
        public List<PartOut> parts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PartOut {
        public String text;
        @JsonProperty("inline_data")
        public Map<String, Object> inlineData;
    }
}

