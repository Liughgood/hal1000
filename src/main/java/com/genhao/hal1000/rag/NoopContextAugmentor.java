package com.genhao.hal1000.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "hal1000.rag.enabled", havingValue = "false", matchIfMissing = true)
public class NoopContextAugmentor implements ContextAugmentor {
    @Override
    public String augmentSystemPrompt(String conversationId, String userQuery) {
        return "";
    }
}

