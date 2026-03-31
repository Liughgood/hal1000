package com.genhao.hal1000.llm;

import reactor.core.publisher.Flux;

import java.util.List;

public interface LlmGateway {

    record SimpleMessage(String role, String content) {
    }

    record StreamEvent(String delta) {
    }

    Flux<StreamEvent> streamReply(List<SimpleMessage> messages);
}

