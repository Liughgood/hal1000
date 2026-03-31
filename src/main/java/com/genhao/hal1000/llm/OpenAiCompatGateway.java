package com.genhao.hal1000.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.genhao.hal1000.llm.OpenAiChatDtos.*;

@Component
public class OpenAiCompatGateway implements LlmGateway {

    private final LlmProperties props;
    private final ObjectMapper objectMapper;
    private final WebClient client;

    public OpenAiCompatGateway(LlmProperties props) {
        this.props = props;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        var b = WebClient.builder()
                .baseUrl(props.getBaseUrl() == null || props.getBaseUrl().isBlank() ? "https://api.openai.com" : props.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
            b = b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey());
        }
        this.client = b.build();
    }

    @Override
    public Flux<StreamEvent> streamReply(List<SimpleMessage> messages) {
        var outMsgs = new ArrayList<ChatMessage>(messages.size());
        for (var m : messages) {
            outMsgs.add(new ChatMessage(m.role(), m.content()));
        }
        var req = new ChatCompletionsRequest(
                props.getModel(),
                outMsgs,
                true,
                null,
                null
        );

        return client
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(req)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .transform(OpenAiCompatGateway::splitSseEvents)
                .flatMap(this::parseChunk);
    }

    private Flux<StreamEvent> parseChunk(String event) {
        var lines = event.split("\n");
        var dataLines = new ArrayList<String>();
        for (var line : lines) {
            if (line.startsWith("data:")) {
                dataLines.add(line.substring(5).trim());
            }
        }
        if (dataLines.isEmpty()) return Flux.empty();
        var data = String.join("\n", dataLines);
        if ("[DONE]".equals(data)) return Flux.empty();

        try {
            var chunk = objectMapper.readValue(data, StreamChunk.class);
            if (chunk.choices() != null && !chunk.choices().isEmpty()) {
                var delta = chunk.choices().get(0).delta();
                if (delta != null && delta.content() != null && !delta.content().isBlank()) {
                    return Flux.just(new StreamEvent(delta.content()));
                }
            }
            return Flux.empty();
        } catch (Exception e) {
            return Flux.empty();
        }
    }

    static Flux<String> splitSseEvents(Flux<String> inbound) {
        return Flux.create(sink -> {
            final StringBuilder buf = new StringBuilder();
            inbound.subscribe(
                    next -> {
                        buf.append(next);
                        int idx;
                        while ((idx = buf.indexOf("\n\n")) >= 0) {
                            var evt = buf.substring(0, idx);
                            buf.delete(0, idx + 2);
                            sink.next(evt);
                        }
                    },
                    sink::error,
                    () -> {
                        if (!buf.isEmpty()) sink.next(buf.toString());
                        sink.complete();
                    }
            );
        });
    }
}

