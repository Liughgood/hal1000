package com.genhao.hal1000.chat.service;

import lombok.Data;

@Data
public class RequestMetrics {
    private String model;
    private long startedAtMs;
    private long firstTokenAtMs;
    private long endedAtMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String error;

    public long getLatencyMs() {
        return endedAtMs > 0 ? (endedAtMs - startedAtMs) : -1;
    }

    public long getTtfbMs() {
        return firstTokenAtMs > 0 ? (firstTokenAtMs - startedAtMs) : -1;
    }
}

