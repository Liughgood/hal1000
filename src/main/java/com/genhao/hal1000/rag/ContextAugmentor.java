package com.genhao.hal1000.rag;

/**
 * RAG extension point.
 * For M1 this is a no-op, but ChatService can call it later without refactor.
 */
public interface ContextAugmentor {
    String augmentSystemPrompt(String userQuery);
}

