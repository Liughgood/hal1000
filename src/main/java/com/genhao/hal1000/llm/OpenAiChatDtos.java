package com.genhao.hal1000.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public final class OpenAiChatDtos {
    private OpenAiChatDtos() {
    }

    public record ChatMessage(
            String role,
            String content
    ) {
    }

    public record ChatCompletionsRequest(
            String model,
            List<ChatMessage> messages,
            @JsonProperty("stream") boolean stream,
            @JsonProperty("temperature") Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StreamChunk(
            String id,
            List<Choice> choices,
            Usage usage
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            Delta delta,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(
            String role,
            String content,
            @JsonProperty("tool_calls") List<Map<String, Object>> toolCalls
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens
    ) {
    }
}

