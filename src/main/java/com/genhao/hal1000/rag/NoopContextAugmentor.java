package com.genhao.hal1000.rag;

import org.springframework.stereotype.Component;

@Component
public class NoopContextAugmentor implements ContextAugmentor {
    @Override
    public String augmentSystemPrompt(String userQuery) {
        return "";
    }
}

